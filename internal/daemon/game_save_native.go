package daemon

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/snapshot"
	"github.com/opensave/opensave/internal/store"
)

// runGameSaveScopeUpload sends a conventional local snapshot through the
// native game-save CAS protocol. handled is false only when the server is an
// older build with no scope route, allowing the caller to use the legacy
// archive/head protocol during a staged NAS upgrade.
func (d *Daemon) runGameSaveScopeUpload(zipPath, legacyName string) (handled bool, err error) {
	gameID, branch, localSnapshotID, ok := snapshot.ParseExportEntryName(legacyName)
	if !ok {
		return false, nil
	}
	game, err := d.Store.GetGame(gameID)
	if err != nil {
		return true, err
	}
	settings, err := d.Store.GetSettings()
	if err != nil {
		return true, err
	}

	// One device must submit its snapshots in source order. Without this, two
	// overlapping local uploads can both observe the same parent and turn the
	// newer local snapshot into a needless conflict copy.
	d.cloudRd.native.Lock()
	defer d.cloudRd.native.Unlock()

	scope, err := d.Cloud.GetGameSaveScope(game.SaveType, game.ID)
	if errors.Is(err, cloud.ErrGameSaveScopesUnsupported) {
		return false, nil
	}
	if err != nil {
		return true, err
	}

	archive, done, err := snapshot.OpenArchive(zipPath)
	if err != nil {
		return true, err
	}
	defer done()
	digest, err := archiveSHA256(archive)
	if err != nil {
		return true, err
	}
	remoteSnapshotID := cloud.GameSaveSnapshotID(localSnapshotID, settings.NodeID)
	archiveName := cloud.GameSaveArchiveName(game.ID, branch, localSnapshotID, settings.NodeID)
	if remoteSnapshotID == "" || archiveName == "" {
		return true, fmt.Errorf("cannot create a native game-save archive identity")
	}

	// A prior response may have been lost after the service committed the CAS
	// write. The current head proves success only when it names these exact
	// immutable bytes; any mismatch is kept out of the automatic path.
	if scope.Head != nil && scope.Head.ID == remoteSnapshotID {
		if scope.Head.Archive != archiveName || !strings.EqualFold(scope.Head.ContentDigest, digest) {
			return true, fmt.Errorf("game-save current head %q does not match this device's immutable archive", remoteSnapshotID)
		}
		d.confirmNativeGameSaveHead(game.ID, localSnapshotID, archiveName, remoteSnapshotID)
		return true, nil
	}

	if err := d.Cloud.Upload(archive, archiveName); err != nil {
		return true, err
	}

	// Every snapshot is archived, but only the active current snapshot may move
	// the scope head. A restore-safety copy or a snapshot overtaken while this
	// upload was queued remains history rather than becoming an authority.
	d.cloudRd.heads.Lock()
	record, _, recordErr := d.Store.GetCloudHead(game.ID)
	current := recordErr == nil && record.Snapshot == localSnapshotID && game.ActiveBranch == branch
	knownHead := ""
	if recordErr == nil {
		knownHead = record.NativeHead
	}
	d.cloudRd.heads.Unlock()
	if !current {
		return true, nil
	}
	if scope.Head == nil && knownHead != "" {
		// A scope that was previously confirmed cannot silently become a new
		// scope. The service may have been reset or pointed at a different data
		// directory; creating a new head here would erase the causal boundary.
		return true, fmt.Errorf("game-save scope lost confirmed head %q; refusing to create a replacement automatically", knownHead)
	}

	candidate := cloud.GameSaveScopeCandidate{
		ID:            remoteSnapshotID,
		Archive:       archiveName,
		Parent:        knownHead,
		ContentDigest: digest,
		CreatedAt:     snapshotCreatedAt(localSnapshotID),
	}
	if scope.Head != nil && knownHead != scope.Head.ID {
		return true, d.retainNativeGameSaveConflict(game, candidate, scope.Head.ID)
	}
	if scope.Head == nil {
		candidate.Parent = ""
	}
	stored, err := d.Cloud.PutGameSaveScopeSnapshot(game.SaveType, game.ID, candidate, candidate.Parent)
	if err == nil {
		d.confirmNativeGameSaveHead(game.ID, localSnapshotID, archiveName, stored.ID)
		return true, nil
	}
	var conflict *cloud.GameSaveScopeConflictError
	if errors.As(err, &conflict) {
		return true, d.retainNativeGameSaveConflict(game, candidate, conflict.Head)
	}
	return true, err
}

func archiveSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func snapshotCreatedAt(snapshotID string) time.Time {
	stamp := snapIDToTimestamp(snapshotID)
	if created, err := time.Parse("2006-01-02T15:04:05.000Z", stamp); err == nil {
		return created.UTC()
	}
	return time.Now().UTC()
}

// confirmNativeGameSaveHead stores only a server-confirmed scope id. It is
// intentionally updated even if a newer local snapshot was created while an
// upload was in flight: that newer snapshot can safely use this one as its CAS
// parent, but its own archive filename remains untouched.
func (d *Daemon) confirmNativeGameSaveHead(gameID, localSnapshotID, archiveName, nativeHead string) {
	d.cloudRd.heads.Lock()
	defer d.cloudRd.heads.Unlock()
	record, _, err := d.Store.GetCloudHead(gameID)
	if err != nil {
		return
	}
	record.NativeHead = nativeHead
	if record.Snapshot == localSnapshotID {
		record.File = archiveName
	}
	if err := d.Store.SetCloudHead(record); err != nil && d.Log != nil {
		d.Log.Log("warn", fmt.Sprintf("cloud: could not record native game-save head for %q: %v", gameID, err))
	}
}

// retainNativeGameSaveConflict persists the divergent branch after its ZIP is
// already immutable on the server. A successful conflict retention is a
// completed backup, not a retryable failure that might later overwrite the
// current head.
func (d *Daemon) retainNativeGameSaveConflict(game store.Game, candidate cloud.GameSaveScopeCandidate, currentHead string) error {
	if _, err := d.Cloud.PutGameSaveScopeConflict(game.SaveType, game.ID, candidate); err != nil {
		return err
	}
	if d.Log != nil {
		d.Log.Log("warn", fmt.Sprintf("cloud: retained %q as a game-save conflict copy; cloud head remains %q", game.Name, currentHead))
	}
	return nil
}

// checkNativeGameSaveScopes reads the authoritative server scopes instead of
// inferring heads from timestamped ZIP announcements. false means the route is
// absent on an older service and the caller should use the legacy flow.
func (d *Daemon) checkNativeGameSaveScopes() bool {
	games, err := d.Store.ListGames()
	if err != nil {
		return true
	}
	if len(games) == 0 {
		d.setCloudOffers(nil)
		return true
	}
	settings, err := d.Store.GetSettings()
	if err != nil {
		return true
	}
	var offers []CloudOffer
	for _, game := range games {
		scope, err := d.Cloud.GetGameSaveScope(game.SaveType, game.ID)
		if errors.Is(err, cloud.ErrGameSaveScopesUnsupported) {
			return false
		}
		if err != nil {
			if d.Log != nil && !cloud.IsNotConfigured(err) {
				d.Log.Log("warn", fmt.Sprintf("cloud: could not read game-save scope for %q: %v", game.Name, err))
			}
			return true
		}
		offer, err := d.reconcileNativeGameSaveScope(game, scope, settings.NodeID)
		if err != nil {
			if d.Log != nil {
				d.Log.Log("warn", fmt.Sprintf("cloud: could not reconcile game-save scope for %q: %v", game.Name, err))
			}
			continue
		}
		if offer != nil {
			offers = append(offers, *offer)
		}
	}
	d.setCloudOffers(offers)
	return true
}

func (d *Daemon) reconcileNativeGameSaveScope(game store.Game, scope cloud.GameSaveScope, deviceID string) (*CloudOffer, error) {
	record, err := d.cloudHeadFor(game)
	if err != nil {
		return nil, err
	}
	if scope.Head == nil {
		handled, publishErr := d.publishNativeGameSaveCurrent(game, record)
		if !handled {
			return nil, cloud.ErrGameSaveScopesUnsupported
		}
		return nil, publishErr
	}
	head := scope.Head
	remoteGameID, branch, remoteSnapshotID, validArchive := cloud.ParseGameSaveArchiveName(head.Archive)
	if !validArchive || remoteGameID != game.ID || branch != game.ActiveBranch || remoteSnapshotID != head.ID {
		return nil, fmt.Errorf("game-save scope returned an invalid current archive")
	}
	localRemoteID := cloud.GameSaveSnapshotID(record.Snapshot, deviceID)
	if head.ID == localRemoteID {
		d.confirmNativeGameSaveHead(game.ID, record.Snapshot, head.Archive, head.ID)
		return nil, nil
	}

	if record.NativeHead == head.ID {
		// This device already confirmed the server head. It may have created a
		// newer local snapshot while offline; submit it now using that parent.
		if nativeLocalSnapshotPending(record, deviceID) {
			handled, publishErr := d.publishNativeGameSaveCurrent(game, record)
			if !handled {
				return nil, cloud.ErrGameSaveScopesUnsupported
			}
			return nil, publishErr
		}
		return nil, nil
	}

	follows := nativeScopeFollows(scope, record.NativeHead)
	localPending := nativeLocalSnapshotPending(record, deviceID)
	state := d.saveStateOf(game)
	if state == saveInUse {
		return nil, nil
	}
	emptyNewDevice := false
	if !follows && d.neverHeldFiles(game) {
		follows, state, emptyNewDevice = true, saveUnchanged, true
	}
	if follows && !localPending && state == saveUnchanged && (emptyNewDevice || record.NativeHead != "") && game.AutoSync {
		settings, err := d.Store.GetSettings()
		if err == nil && settings.CloudAutoPull {
			if err := d.pullFromCloud(game, head.Archive, head.DeviceName); err != nil {
				return nil, err
			}
			if d.OnCloudPulled != nil {
				d.OnCloudPulled(CloudPulled{GameID: game.ID, GameName: game.Name, DeviceName: head.DeviceName, SnapshotID: head.ID})
			}
			return nil, nil
		}
	}

	savedAt := head.ReceivedAt
	if savedAt.IsZero() {
		savedAt = head.CreatedAt
	}
	reason := "history-diverged"
	switch {
	case record.NativeHead == "":
		reason = "local-baseline-unknown"
	case localPending:
		reason = "local-changed"
	case state == saveChanged:
		reason = "local-changed"
	case state == saveUnknown:
		reason = "local-baseline-unknown"
	}
	return &CloudOffer{
		GameID:       game.ID,
		GameName:     game.Name,
		SnapshotID:   head.ID,
		FileName:     head.Archive,
		DeviceName:   head.DeviceName,
		SavedAt:      savedAt.UTC().Format(time.RFC3339Nano),
		Provider:     cloud.GameSaveProvider,
		ReviewReason: reason,
		Diverged:     true,
	}, nil
}

// nativeLocalSnapshotPending reports whether the current local save is newer
// than the last server-confirmed native head. A clean local snapshot normally
// has a different local id from its remote --dev- id, so compare its derived
// remote id rather than comparing the two storage domains directly.
func nativeLocalSnapshotPending(record store.CloudHead, deviceID string) bool {
	if record.Snapshot == "" || record.Snapshot == record.NativeHead {
		return false
	}
	return cloud.GameSaveSnapshotID(record.Snapshot, deviceID) != record.NativeHead
}

func nativeScopeFollows(scope cloud.GameSaveScope, ancestor string) bool {
	if ancestor == "" || scope.Head == nil {
		return false
	}
	if scope.Head.ID == ancestor {
		return true
	}
	snapshots := make(map[string]cloud.GameSaveScopeSnapshot, len(scope.Snapshots))
	for _, item := range scope.Snapshots {
		if !item.Conflict {
			snapshots[item.ID] = item
		}
	}
	current := scope.Head.ID
	for steps := 0; current != "" && steps <= len(snapshots); steps++ {
		if current == ancestor {
			return true
		}
		item, ok := snapshots[current]
		if !ok {
			return false
		}
		current = item.Parent
	}
	return false
}

func (d *Daemon) publishNativeGameSaveCurrent(game store.Game, record store.CloudHead) (bool, error) {
	if record.Snapshot == "" || record.Snapshot == record.NativeHead {
		return true, nil
	}
	snap, err := d.Store.GetSnapshot(record.Snapshot)
	if err != nil || snap.GameID != game.ID || snap.BranchName != game.ActiveBranch {
		return true, err
	}
	if !snapshot.ArchiveExists(snap.ZipPath) {
		return true, fmt.Errorf("current snapshot archive is missing")
	}
	return d.runGameSaveScopeUpload(snap.ZipPath, cloudSnapshotName(game.ID, snap.BranchName, snap.ID))
}
