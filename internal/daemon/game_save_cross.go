package daemon

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/ignore"
	"github.com/opensave/opensave/internal/snapshot"
	"github.com/opensave/opensave/internal/store"
)

// Cross-device restore: taking a save another device uploaded for the same
// game, without pretending it is this device's own cloud lineage.
//
// The source scope is read-only. Its head and parent chain must not move: the
// archive is a content source, not a version this device follows. After the
// files are in place, an ordinary snapshot is taken and published by the
// normal upload path as a child of THIS device's confirmed scope head.

var (
	// ErrCrossRestoreInvalid marks a request that cannot be served as asked:
	// a bad member id, a conflict copy, or an unusable mapping. The API
	// answers 400.
	ErrCrossRestoreInvalid = errors.New("invalid cross-device restore request")
	// ErrCrossRestoreInUse is the 409 case: the game has its save files open,
	// so replacing them now would race the running game.
	ErrCrossRestoreInUse = errors.New("the save is in use")
)

// crossRestoreSaveState is saveStateOf, indirected so tests can exercise the
// in-use refusal on platforms where an open save cannot be observed.
var crossRestoreSaveState = func(d *Daemon, game store.Game) saveState {
	return d.saveStateOf(game)
}

var (
	crossRestoreMemberRe  = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)
	crossRestoreArchiveRe = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)
)

// CrossRestorePlan is the read-only answer to "what would restoring this
// cloud version do?".
type CrossRestorePlan struct {
	GameID           string                       `json:"gameId"`
	MemberGameID     string                       `json:"memberGameId"`
	SnapshotID       string                       `json:"snapshotId"`
	DeviceName       string                       `json:"deviceName"`
	Platform         string                       `json:"platform"`
	CreatedAt        time.Time                    `json:"createdAt"`
	Format           string                       `json:"format"`
	SourceGameName   string                       `json:"sourceGameName"`
	ArchiveLocations []snapshot.ForeignLocation   `json:"archiveLocations"`
	LocalLocations   []CrossRestoreLocalLocation  `json:"localLocations"`
	Suggested        []snapshot.ForeignAssignment `json:"suggested"`
	IgnoredEntries   int                          `json:"ignoredEntries"`
}

// CrossRestoreLocalLocation is one place on this device a restore can write
// to. Root is "" for the main save folder.
type CrossRestoreLocalLocation struct {
	Root string `json:"root"`
	Path string `json:"path"`
	Kind string `json:"kind"`
}

// CrossRestoreResult names the snapshots a completed restore produced.
type CrossRestoreResult struct {
	SafetySnapshotID   string                       `json:"safetySnapshotId"`
	RestoredSnapshotID string                       `json:"restoredSnapshotId"`
	Restored           []snapshot.ForeignAssignment `json:"restored"`
}

// PlanCrossDeviceRestore downloads the chosen version to a temporary folder,
// inspects it, and reports the mappings a user can confirm. It creates no
// snapshot, changes no save file, and writes nothing to the cloud.
func (d *Daemon) PlanCrossDeviceRestore(gameID, memberGameID, snapshotID string) (CrossRestorePlan, error) {
	game, snap, zipPath, cleanup, err := d.crossRestoreSource(gameID, memberGameID, snapshotID)
	if err != nil {
		return CrossRestorePlan{}, err
	}
	defer cleanup()

	archive, err := snapshot.InspectForeignArchive(zipPath)
	if err != nil {
		return CrossRestorePlan{}, fmt.Errorf("%w: the cloud save manifest or archive cannot be read: %v", snapshot.ErrForeignArchive, err)
	}
	locals, err := d.crossRestoreLocalLocations(game)
	if err != nil {
		return CrossRestorePlan{}, err
	}
	return CrossRestorePlan{
		GameID:           game.ID,
		MemberGameID:     memberGameID,
		SnapshotID:       snapshotID,
		DeviceName:       snap.DeviceName,
		Platform:         snap.Platform,
		CreatedAt:        snap.CreatedAt,
		Format:           archive.Format,
		SourceGameName:   archive.GameName,
		ArchiveLocations: archive.Locations,
		LocalLocations:   locals,
		Suggested:        crossRestoreSuggestions(archive, locals),
		IgnoredEntries:   archive.IgnoredEntries,
	}, nil
}

// ApplyCrossDeviceRestore restores the mapped locations. A safety snapshot is
// mandatory; a normal snapshot is then published through the usual upload
// path, with this device's confirmed head as its parent.
func (d *Daemon) ApplyCrossDeviceRestore(gameID, memberGameID, snapshotID string, assignments []snapshot.ForeignAssignment) (CrossRestoreResult, error) {
	game, snap, zipPath, cleanup, err := d.crossRestoreSource(gameID, memberGameID, snapshotID)
	if err != nil {
		return CrossRestoreResult{}, err
	}
	defer cleanup()

	if crossRestoreSaveState(d, game) == saveInUse {
		return CrossRestoreResult{}, fmt.Errorf("%w: close the game and try again", ErrCrossRestoreInUse)
	}

	sourceName := strings.TrimSpace(snap.DeviceName)
	if sourceName == "" {
		sourceName = memberGameID
	}
	safety, err := d.Snapshots.RestoreForeign(
		game.ID, zipPath, assignments, ignore.Parse(game.SyncIgnore),
		"Safety copy before restoring a save from "+sourceName)
	if err != nil {
		return CrossRestoreResult{}, err
	}

	// The files on disk are now that snapshot. Record it as this device's
	// baseline, or the watcher sees the restore as fresh game data and takes
	// a second, duplicate snapshot.
	if hash, err := d.currentContentHash(game); err == nil {
		_ = d.Store.SetLastManifestHash(game.ID, hash)
	}

	result := CrossRestoreResult{
		SafetySnapshotID: safety.ID,
		Restored:         append([]snapshot.ForeignAssignment{}, assignments...),
	}
	restored, err := d.Snapshots.Create(game.ID, "Restored from "+sourceName, false)
	if err != nil {
		// The files are restored and the safety copy exists. A failed
		// publication snapshot is worth reporting, but it does not undo the
		// restore.
		if d.Log != nil {
			d.Log.Log("warn", fmt.Sprintf(
				"cloud: restored %q from %s but could not snapshot the result: %v", game.Name, sourceName, err))
		}
	} else {
		result.RestoredSnapshotID = restored.ID
	}

	if d.Log != nil {
		d.Log.Log("success", fmt.Sprintf("cloud: restored %q from %s's cloud save", game.Name, sourceName))
	}
	if d.OnGameChanged != nil {
		d.OnGameChanged(game.ID)
	}
	return result, nil
}

// crossRestoreSource performs everything both the plan and the apply need:
// validate the request, read the source scope, find the version, download
// its archive, and verify its digest. cleanup is always safe to call.
func (d *Daemon) crossRestoreSource(gameID, memberGameID, snapshotID string) (
	game store.Game, snap cloud.GameSaveScopeSnapshot, zipPath string, cleanup func(), err error) {
	cleanup = func() {}

	game, err = d.Store.GetGame(gameID)
	if err != nil {
		return game, snap, "", cleanup, err
	}
	cfg, err := d.Store.GetCloudConfig()
	if err != nil {
		return game, snap, "", cleanup, err
	}
	if cfg.Provider != cloud.GameSaveProvider || !cloud.Ready(cfg) {
		return game, snap, "", cleanup, fmt.Errorf("%w: the cloud is not configured for game-save", ErrCrossRestoreInvalid)
	}

	if strings.TrimSpace(snapshotID) == "" {
		return game, snap, "", cleanup, fmt.Errorf("%w: snapshotId is required", ErrCrossRestoreInvalid)
	}
	if memberGameID == "" || len(memberGameID) > 128 || !crossRestoreMemberRe.MatchString(memberGameID) ||
		memberGameID == "." || memberGameID == ".." {
		return game, snap, "", cleanup, fmt.Errorf("%w: memberGameId is invalid", ErrCrossRestoreInvalid)
	}
	if memberGameID == game.ID {
		return game, snap, "", cleanup, fmt.Errorf("%w: cannot restore from this device's own cloud scope", ErrCrossRestoreInvalid)
	}

	if err := d.requireSameGameGroup(game, memberGameID); err != nil {
		return game, snap, "", cleanup, err
	}

	scope, err := d.Cloud.GetGameSaveScope(game.SaveType, memberGameID)
	if err != nil {
		if errors.Is(err, cloud.ErrGameSaveScopesUnsupported) {
			return game, snap, "", cleanup, fmt.Errorf("%w: the cloud service does not support game-save scopes", ErrCrossRestoreInvalid)
		}
		return game, snap, "", cleanup, err
	}

	found := false
	if scope.Head != nil && scope.Head.ID == snapshotID {
		snap = *scope.Head
		found = true
	}
	if !found {
		for _, item := range scope.Snapshots {
			if item.ID == snapshotID {
				snap = item
				found = true
				break
			}
		}
	}
	if !found {
		return game, snap, "", cleanup, fmt.Errorf("cloud version %q was not found: %w", snapshotID, store.ErrNotFound)
	}
	if snap.Conflict {
		return game, snap, "", cleanup, fmt.Errorf("%w: conflict copies cannot be restored", ErrCrossRestoreInvalid)
	}

	// The archive name comes from the service, so it is validated before it
	// is joined to a path. Dots are allowed in ordinary names, but "." and
	// ".." would step outside the temporary folder.
	if !crossRestoreArchiveRe.MatchString(snap.Archive) || snap.Archive == "." || snap.Archive == ".." {
		return game, snap, "", cleanup, fmt.Errorf("game-save scope returned an invalid archive name")
	}

	tmp, err := os.MkdirTemp("", "opensave-cross-restore-*")
	if err != nil {
		return game, snap, "", cleanup, err
	}
	defer func() {
		if err != nil {
			_ = os.RemoveAll(tmp)
			cleanup = func() {}
		}
	}()
	cleanup = func() { _ = os.RemoveAll(tmp) }

	zipPath = filepath.Join(tmp, snap.Archive)
	if err := d.Cloud.DownloadFile(cloud.CloudFile{
		ID:   cloud.SaveTypeScope(game.SaveType),
		Name: snap.Archive,
	}, zipPath); err != nil {
		return game, snap, zipPath, cleanup, fmt.Errorf("download cloud version: %w", err)
	}
	if digest := strings.TrimSpace(snap.ContentDigest); digest != "" {
		actual, hashErr := archiveSHA256(zipPath)
		if hashErr != nil {
			return game, snap, zipPath, cleanup, hashErr
		}
		if !strings.EqualFold(actual, digest) {
			return game, snap, zipPath, cleanup, fmt.Errorf(
				"cloud version failed verification: the archive does not match its recorded checksum")
		}
	}
	return game, snap, zipPath, cleanup, nil
}

// requireSameGameGroup refuses a source scope the service does not group
// with this game. The UI only offers members of the game's own group, but the
// API takes any scope id, and the same type namespace alone holds every other
// game of that type: restoring one of those would replace this save with a
// different game's.
func (d *Daemon) requireSameGameGroup(game store.Game, memberGameID string) error {
	games, err := d.Cloud.ListGameSaveGames()
	if errors.Is(err, cloud.ErrGameSaveGamesUnsupported) {
		return fmt.Errorf("%w: the cloud service does not group games, so another device's save cannot be matched to this one", ErrCrossRestoreInvalid)
	}
	if err != nil {
		return fmt.Errorf("read game-save game groups: %w", err)
	}
	saveType := cloud.SaveTypeScope(game.SaveType)
	if group := findGameSaveGroup(games, game); group != nil {
		for _, member := range group.Scopes {
			if member.Type == saveType && member.GameID == memberGameID {
				return nil
			}
		}
	}
	return fmt.Errorf("%w: %q is not grouped with this game in the cloud", ErrCrossRestoreInvalid, memberGameID)
}

// crossRestoreLocalLocations is the main save folder plus every extra
// location this device has actually mapped. An unmapped location is not a
// place a restore can write to, so it is not offered.
func (d *Daemon) crossRestoreLocalLocations(game store.Game) ([]CrossRestoreLocalLocation, error) {
	locals := []CrossRestoreLocalLocation{{
		Root: "",
		Path: game.SavePath,
		Kind: crossRestoreLocalKind(game.SaveType),
	}}
	roots, err := d.Store.GameRootPaths(game.ID)
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(roots))
	for name, path := range roots {
		if strings.TrimSpace(path) == "" {
			continue
		}
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		locals = append(locals, CrossRestoreLocalLocation{
			Root: name,
			Path: roots[name],
			Kind: "",
		})
	}
	return locals, nil
}

// crossRestoreLocalKind maps this device's save type to the location kind the
// Android manifest uses. The names are the detector's, not this package's.
func crossRestoreLocalKind(saveType string) string {
	switch cloud.SaveTypeScope(saveType) {
	case "switch":
		return "switch-title"
	case "emulator-ppsspp":
		return "ppsspp"
	case "pc":
		return "gamehub-pc"
	default:
		return ""
	}
}

// crossRestoreSuggestions is only a preselection for the UI. The user always
// confirms; a wrong guess here must never be applied on its own.
func crossRestoreSuggestions(archive snapshot.ForeignArchive, locals []CrossRestoreLocalLocation) []snapshot.ForeignAssignment {
	suggested := []snapshot.ForeignAssignment{}
	if archive.Format == snapshot.ForeignFormatPC {
		// A PC archive is one folder's contents. With more than one local
		// location there is no honest way to choose between them.
		if len(locals) == 1 {
			suggested = append(suggested, snapshot.ForeignAssignment{
				ArchiveLocation: snapshot.ForeignPCRoot,
				LocalRoot:       locals[0].Root,
			})
		}
		return suggested
	}

	archiveByKind := map[string][]snapshot.ForeignLocation{}
	for _, location := range archive.Locations {
		if location.Kind != "" {
			archiveByKind[location.Kind] = append(archiveByKind[location.Kind], location)
		}
	}
	localByKind := map[string][]CrossRestoreLocalLocation{}
	for _, location := range locals {
		if location.Kind != "" {
			localByKind[location.Kind] = append(localByKind[location.Kind], location)
		}
	}
	for _, local := range locals {
		if local.Kind == "" {
			continue
		}
		archiveMatches := archiveByKind[local.Kind]
		localMatches := localByKind[local.Kind]
		if len(archiveMatches) == 1 && len(localMatches) == 1 {
			suggested = append(suggested, snapshot.ForeignAssignment{
				ArchiveLocation: archiveMatches[0].ID,
				LocalRoot:       local.Root,
			})
		}
	}
	return suggested
}
