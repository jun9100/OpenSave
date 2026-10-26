package daemon

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/snapshot"
	"github.com/opensave/opensave/internal/store"
)

// GameSaveScopeStatus is a read-only summary of one native game-save scope.
// The server head is authoritative only because its parent lineage passed CAS;
// timestamps are retained as display metadata and never select a winner.
type GameSaveScopeStatus struct {
	Provider           string                    `json:"provider"`
	Configured         bool                      `json:"configured"`
	NativeSupported    bool                      `json:"nativeSupported"`
	GameID             string                    `json:"gameId"`
	GameName           string                    `json:"gameName"`
	SaveType           string                    `json:"saveType"`
	Automatic          bool                      `json:"automatic"`
	State              string                    `json:"state"`
	Detail             string                    `json:"detail"`
	LocalSnapshot      string                    `json:"localSnapshot"`
	LocalConfirmedHead string                    `json:"localConfirmedHead"`
	CloudHead          string                    `json:"cloudHead"`
	CloudSnapshot      *GameSaveScopeStatusItem  `json:"cloudSnapshot,omitempty"`
	Snapshots          []GameSaveScopeStatusItem `json:"snapshots"`
	SnapshotCount      int                       `json:"snapshotCount"`
	ConflictCount      int                       `json:"conflictCount"`
	Conflicts          []GameSaveScopeStatusItem `json:"conflicts"`
	RestoreBlocked     bool                      `json:"restoreBlocked"`
	Publishable        bool                      `json:"publishable"`
}

// GameSaveScopeStatusItem is deliberately limited to the lineage and source
// metadata a player needs to identify a version. Archive contents stay opaque
// to both OpenSave and the service.
type GameSaveScopeStatusItem struct {
	ID         string    `json:"id"`
	Parent     string    `json:"parent"`
	Archive    string    `json:"archive"`
	CreatedAt  time.Time `json:"createdAt"`
	ReceivedAt time.Time `json:"receivedAt"`
	Device     string    `json:"device"`
	DeviceName string    `json:"deviceName"`
	Platform   string    `json:"platform"`
	Conflict   bool      `json:"conflict"`
	ConflictOf string    `json:"conflictOf"`
}

const (
	gameSaveStatusUpToDate        = "up_to_date"
	gameSaveStatusUploadPending   = "upload_pending"
	gameSaveStatusDownloadPending = "download_pending"
	gameSaveStatusConflict        = "conflict"
	gameSaveStatusOffline         = "offline"
	gameSaveStatusError           = "error"
	gameSaveStatusDisabled        = "disabled"
	gameSaveStatusLegacy          = "legacy"
	gameSaveStatusInUse           = "in_use"
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

func gameSaveStatusItem(snapshot cloud.GameSaveScopeSnapshot) GameSaveScopeStatusItem {
	return GameSaveScopeStatusItem{
		ID:         snapshot.ID,
		Parent:     snapshot.Parent,
		Archive:    snapshot.Archive,
		CreatedAt:  snapshot.CreatedAt,
		ReceivedAt: snapshot.ReceivedAt,
		Device:     snapshot.Device,
		DeviceName: snapshot.DeviceName,
		Platform:   snapshot.Platform,
		Conflict:   snapshot.Conflict,
		ConflictOf: snapshot.ConflictOf,
	}
}

// sortGameSaveStatusItems is presentation-only. The service's accepted head,
// not the displayed receive time, remains the authority when versions differ.
func sortGameSaveStatusItems(items []GameSaveScopeStatusItem) {
	sort.Slice(items, func(i, j int) bool {
		if !items[i].ReceivedAt.Equal(items[j].ReceivedAt) {
			return items[i].ReceivedAt.After(items[j].ReceivedAt)
		}
		return items[i].ID > items[j].ID
	})
}

func gameSaveStatusNetworkError(err error) bool {
	var networkErr net.Error
	return errors.As(err, &networkErr)
}

func addGameSaveStatusDetail(status *GameSaveScopeStatus, detail string) {
	if status.Detail == "" {
		status.Detail = detail
		return
	}
	status.Detail += " " + detail
}

// GameSaveStatus inspects one typed native scope without creating snapshots,
// uploading archives, advancing a head, retaining a conflict, or changing the
// local cloud-head record. It is deliberately stricter than the background
// reconciler: missing lineage is reported for review rather than repaired by
// a status read.
func (d *Daemon) GameSaveStatus(gameID string) (GameSaveScopeStatus, error) {
	game, err := d.Store.GetGame(gameID)
	if err != nil {
		return GameSaveScopeStatus{}, err
	}
	status := GameSaveScopeStatus{
		GameID:    game.ID,
		GameName:  game.Name,
		SaveType:  cloud.SaveTypeScope(game.SaveType),
		Automatic: game.AutoSync,
		Snapshots: []GameSaveScopeStatusItem{},
		Conflicts: []GameSaveScopeStatusItem{},
	}

	cfg, err := d.Store.GetCloudConfig()
	if err != nil {
		return GameSaveScopeStatus{}, err
	}
	status.Provider = cfg.Provider
	if cfg.Provider != cloud.GameSaveProvider {
		status.State = gameSaveStatusDisabled
		status.Detail = "The configured cloud provider does not expose native game-save scope status."
		return status, nil
	}
	if !cloud.Ready(cfg) {
		status.State = gameSaveStatusDisabled
		status.Detail = "game-save is not fully configured."
		return status, nil
	}
	status.Configured = true

	settings, err := d.Store.GetSettings()
	if err != nil {
		return GameSaveScopeStatus{}, err
	}
	record, recorded, err := d.Store.GetCloudHead(game.ID)
	if err != nil {
		return GameSaveScopeStatus{}, err
	}
	if recorded {
		status.LocalSnapshot = record.Snapshot
		status.LocalConfirmedHead = record.NativeHead
	}
	if status.LocalSnapshot == "" {
		snapshots, listErr := d.Store.ListSnapshots(game.ID, game.ActiveBranch)
		if listErr != nil {
			return GameSaveScopeStatus{}, listErr
		}
		if len(snapshots) > 0 {
			status.LocalSnapshot = snapshots[0].ID
		}
	}

	scope, err := d.Cloud.GetGameSaveScope(status.SaveType, game.ID)
	if errors.Is(err, cloud.ErrGameSaveScopesUnsupported) {
		status.State = gameSaveStatusLegacy
		status.Detail = "The service does not expose native scope lineage, source devices, or retained conflicts."
		return status, nil
	}
	if err != nil {
		status.State = gameSaveStatusError
		if gameSaveStatusNetworkError(err) {
			status.State = gameSaveStatusOffline
		}
		status.Detail = fmt.Sprintf("Could not read native game-save scope status: %v", err)
		return status, nil
	}
	status.NativeSupported = true
	knownSnapshots := make(map[string]bool, len(scope.Snapshots)+1)
	for _, item := range scope.Snapshots {
		if item.Conflict || knownSnapshots[item.ID] {
			continue
		}
		knownSnapshots[item.ID] = true
		status.Snapshots = append(status.Snapshots, gameSaveStatusItem(item))
	}
	for _, conflict := range scope.Conflicts {
		status.Conflicts = append(status.Conflicts, gameSaveStatusItem(conflict))
	}
	if scope.Head != nil {
		head := gameSaveStatusItem(*scope.Head)
		status.CloudHead = head.ID
		status.CloudSnapshot = &head
		if !head.Conflict && !knownSnapshots[head.ID] {
			status.Snapshots = append(status.Snapshots, head)
		}
	}
	// A conflict may appear both in snapshots and conflicts on older service
	// builds, so the recoverable history deliberately excludes it above.
	status.SnapshotCount = len(status.Snapshots)
	status.ConflictCount = len(status.Conflicts)
	sortGameSaveStatusItems(status.Snapshots)
	sortGameSaveStatusItems(status.Conflicts)

	// A retained conflict is an immutable recovery candidate. It must be
	// resolved explicitly before an ordinary cloud restore can replace a save.
	if status.ConflictCount > 0 {
		status.State = gameSaveStatusConflict
		status.Detail = "The cloud scope has retained conflict copies that need review."
		status.RestoreBlocked = true
		return status, nil
	}

	localState := d.saveStateOf(game)
	if localState == saveInUse {
		status.State = gameSaveStatusInUse
		status.Detail = "The local save is in use, so its sync state cannot be verified yet."
		status.RestoreBlocked = true
		return status, nil
	}
	if scope.Head == nil {
		status.State = gameSaveStatusUploadPending
		if status.LocalSnapshot == "" {
			status.Detail = "No authoritative cloud head exists and no local snapshot is available to publish."
		} else {
			status.Detail = "No authoritative cloud head exists; the local snapshot is waiting to be published."
			status.Publishable = true
		}
		if !status.Automatic {
			addGameSaveStatusDetail(&status, "Automatic sync is off for this game.")
		}
		return status, nil
	}

	// A status read must never backfill NativeHead. Until a normal sync has
	// confirmed the local parent, the client cannot prove that its on-disk
	// content follows the server head.
	if status.LocalConfirmedHead == "" {
		status.State = gameSaveStatusConflict
		status.Detail = "This device has no confirmed native cloud head for the local save."
		status.RestoreBlocked = true
		return status, nil
	}

	localPending := status.LocalSnapshot != "" &&
		cloud.GameSaveSnapshotID(status.LocalSnapshot, settings.NodeID) != status.LocalConfirmedHead
	if scope.Head.ID == status.LocalConfirmedHead {
		switch localState {
		case saveChanged:
			status.State = gameSaveStatusUploadPending
			status.Detail = "Local save changes need a new snapshot before they can be published."
		case saveUnknown:
			status.State = gameSaveStatusConflict
			status.Detail = "The local save has no verified baseline for the confirmed cloud head."
			status.RestoreBlocked = true
		case saveUnchanged:
			if localPending {
				status.State = gameSaveStatusUploadPending
				status.Detail = "A local snapshot is waiting to advance the cloud head."
				status.Publishable = true
			} else {
				status.State = gameSaveStatusUpToDate
				status.Detail = "The local save matches the confirmed cloud head."
			}
		default:
			status.State = gameSaveStatusError
			status.Detail = "The local save state could not be verified."
		}
	} else if nativeScopeFollows(scope, status.LocalConfirmedHead) && !localPending && localState == saveUnchanged {
		status.State = gameSaveStatusDownloadPending
		status.Detail = "The authoritative cloud head follows this device's confirmed head."
	} else {
		status.State = gameSaveStatusConflict
		status.Detail = "The local save and authoritative cloud head do not share a proven continuous lineage."
		status.RestoreBlocked = true
	}
	if !status.Automatic {
		addGameSaveStatusDetail(&status, "Automatic sync is off for this game.")
	}
	return status, nil
}

// PublishCurrentGameSaveHead is the explicit manual-backfill path for the
// native provider. Unlike the legacy "upload every ZIP" action, it submits
// only the current local snapshot through the same CAS lineage checks as an
// automatic upload.
func (d *Daemon) PublishCurrentGameSaveHead(gameID string) error {
	game, err := d.Store.GetGame(gameID)
	if err != nil {
		return err
	}
	record, err := d.cloudHeadFor(game)
	if err != nil {
		return err
	}
	if record.Snapshot == "" {
		return fmt.Errorf("no local snapshot is available to publish")
	}
	handled, err := d.publishNativeGameSaveCurrent(game, record)
	if !handled {
		return cloud.ErrGameSaveScopesUnsupported
	}
	return err
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
