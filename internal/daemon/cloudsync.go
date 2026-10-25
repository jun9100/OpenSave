package daemon

import (
	"archive/zip"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/delta"
	"github.com/opensave/opensave/internal/ignore"
	"github.com/opensave/opensave/internal/snapshot"
	"github.com/opensave/opensave/internal/store"
	"github.com/opensave/opensave/internal/watcher"
)

// Reading the cloud mirror back.
//
// Every snapshot has long been copied to the cloud provider, and nothing ever
// read those copies except a person on the cloud screen. So the one thing a
// cloud copy is best at — carrying a save to a device while the one that made
// it is switched off — needed that person to know to go and look. GitHub
// issue #12 asked for the obvious: say so when there is a newer save up there.
//
// Each device now announces, per game, which snapshot IS its save (a "head",
// see internal/cloud/heads.go) and reads the other devices' heads. A newer
// save that carries on from the one this device has, on a device that has not
// changed its own since, is put in place without asking — the same thing
// syncing between two devices that are both on does. Anything else is asked
// about, because taking it would replace progress made here.

// CloudOffer is a newer save for one game, from another of this person's
// devices, waiting for a yes or a no.
type CloudOffer struct {
	GameID     string `json:"gameId"`
	GameName   string `json:"gameName"`
	SnapshotID string `json:"snapshotId"`
	FileName   string `json:"fileName"`
	DeviceName string `json:"deviceName"`
	SavedAt    string `json:"savedAt"`
	Provider   string `json:"provider"`
	// ReviewReason lets a client explain why this head was not applied
	// automatically. It is a stable code rather than English copy so desktop
	// and Android UIs can localize it.
	ReviewReason string `json:"reviewReason,omitempty"`
	// Diverged is true when this device's save holds progress the other one
	// does not carry — the two moved apart, or never shared a save at all —
	// so taking the other one replaces progress made here.
	Diverged bool `json:"diverged"`
}

// CloudPulled reports a save that was put in place without asking.
type CloudPulled struct {
	GameID     string `json:"gameId"`
	GameName   string `json:"gameName"`
	DeviceName string `json:"deviceName"`
	SnapshotID string `json:"snapshotId"`
}

// How soon after start the cloud is first read, and how often after that. The
// first check is what answers issue #12 — "when I open the app" — and waits
// only long enough for startup to finish. Variables so tests can drive them.
var (
	cloudCheckDelay    = 20 * time.Second
	cloudCheckInterval = 10 * time.Minute
)

// ErrCloudOfferGone is returned for an answer to an offer that is no longer
// open — already taken, dismissed, or overtaken by a newer save.
var ErrCloudOfferGone = errors.New("that save is no longer waiting — check the cloud screen for the newest one")

// cloudReader is the daemon's state for reading the mirror.
type cloudReader struct {
	// check allows one check or pull at a time: two overlapping would read
	// the same head and both restore it.
	check sync.Mutex
	// heads serialises read-modify-write of a game's cloud_heads row, which
	// snapshots, restores, uploads and answers all update from their own
	// goroutines.
	heads sync.Mutex

	offersMu sync.Mutex
	offers   []CloudOffer

	// cache holds heads already downloaded. A head file is never rewritten
	// — each announcement is a new file — so its name identifies its content.
	cacheMu sync.Mutex
	cache   map[string]cloud.Head
}

// cloudListable reports whether a provider can be read back at all. A webhook
// only receives.
func cloudListable(provider string) bool {
	return provider != "" && provider != "webhook"
}

// cloudSnapshotName is the name this device gives a snapshot in the cloud.
func cloudSnapshotName(gameID, branch, snapID string) string {
	return fmt.Sprintf("%s__%s__%s.zip", gameID, branch, snapID)
}

// snapMillis reads the moment out of a snap_<unix ms> id; 0 when it has none.
func snapMillis(snapID string) int64 {
	ms, err := strconv.ParseInt(strings.TrimPrefix(snapID, "snap_"), 10, 64)
	if err != nil {
		return 0
	}
	return ms
}

// stampMillis reads a stored timestamp; 0 when it cannot.
func stampMillis(stamp string) int64 {
	for _, layout := range []string{"2006-01-02T15:04:05.000Z", time.RFC3339Nano} {
		if t, err := time.Parse(layout, stamp); err == nil {
			return t.UnixMilli()
		}
	}
	return 0
}

func nowStamp() string { return time.Now().UTC().Format("2006-01-02T15:04:05.000Z") }

// snapshotContentModifiedAt reads the newest modification time preserved in a
// snapshot archive. Snapshot IDs describe when a backup was made, which is
// not necessarily when its game save last changed: an old save can be copied
// or uploaded much later. A zero result is deliberately treated as unknown by
// cloud sync, never as evidence that the archive is newer.
func snapshotContentModifiedAt(zipPath string) (int64, error) {
	archive, done, err := snapshot.OpenArchive(zipPath)
	if err != nil {
		return 0, err
	}
	defer done()

	zr, err := zip.OpenReader(archive)
	if err != nil {
		return 0, err
	}
	defer zr.Close()

	var newest int64
	for _, file := range zr.File {
		if file.FileInfo().IsDir() || file.Modified.IsZero() {
			continue
		}
		if at := file.Modified.UnixMilli(); at > newest {
			newest = at
		}
	}
	if newest == 0 {
		return 0, errors.New("snapshot has no timestamped save files")
	}
	return newest, nil
}

func (d *Daemon) contentAtForSnapshot(snap store.Snapshot) int64 {
	at, err := snapshotContentModifiedAt(snap.ZipPath)
	if err != nil {
		if d.Log != nil {
			d.Log.Log("warn", fmt.Sprintf("cloud: could not read content time for %s: %v", snap.ID, err))
		}
		return 0
	}
	return at
}

func headContentAt(head cloud.Head) (int64, bool) {
	if head.ContentModifiedAt == "" {
		return 0, false
	}
	at, err := time.Parse(time.RFC3339Nano, head.ContentModifiedAt)
	if err != nil || at.UnixMilli() <= 0 {
		return 0, false
	}
	return at.UnixMilli(), true
}

func contentAtStamp(at int64) string {
	if at <= 0 {
		return ""
	}
	return time.UnixMilli(at).UTC().Format(time.RFC3339Nano)
}

// appendChain adds a save to the end of a chain, keeping the newest
// cloud.MaxHeadChain.
func appendChain(chain []string, snapID string) []string {
	if n := len(chain); n > 0 && chain[n-1] == snapID {
		return chain
	}
	chain = append(append([]string(nil), chain...), snapID)
	if extra := len(chain) - cloud.MaxHeadChain; extra > 0 {
		chain = chain[extra:]
	}
	return chain
}

// noteSnapshotForCloud keeps the game's record current as snapshots are
// taken. Only a snapshot of the save as it stands moves it; a copy kept
// before replacing a save is history, not this device's save.
func (d *Daemon) noteSnapshotForCloud(snap store.Snapshot, current bool) {
	if !current {
		return
	}
	game, err := d.Store.GetGame(snap.GameID)
	if err != nil || snap.BranchName != game.ActiveBranch {
		return
	}
	d.cloudRd.heads.Lock()
	defer d.cloudRd.heads.Unlock()
	rec, _, err := d.Store.GetCloudHead(game.ID)
	if err != nil {
		d.Log.Log("warn", fmt.Sprintf("cloud: could not record %s as %q's save: %v", snap.ID, game.Name, err))
		return
	}
	rec.Snapshot = snap.ID
	rec.Since = snap.Timestamp
	rec.File = cloudSnapshotName(game.ID, snap.BranchName, snap.ID)
	rec.Chain = appendChain(rec.Chain, snap.ID)
	rec.ContentAt = d.contentAtForSnapshot(snap)
	if err := d.Store.SetCloudHead(rec); err != nil {
		d.Log.Log("warn", fmt.Sprintf("cloud: could not record %s as %q's save: %v", snap.ID, game.Name, err))
	}
}

// noteRestoreForCloud records a restored snapshot as the game's save, and
// announces it.
//
// The chain starts again from it. Whatever this device did before the
// restore is not what its save carries on from any more — whether the
// snapshot came from another device or from this one's own past.
func (d *Daemon) noteRestoreForCloud(snap store.Snapshot) {
	game, err := d.Store.GetGame(snap.GameID)
	if err != nil {
		return
	}
	d.cloudRd.heads.Lock()
	rec, _, err := d.Store.GetCloudHead(game.ID)
	if err == nil {
		rec.Snapshot = snap.ID
		rec.Since = nowStamp()
		rec.File = cloudSnapshotName(game.ID, snap.BranchName, snap.ID)
		rec.Chain = []string{snap.ID}
		rec.ContentAt = d.contentAtForSnapshot(snap)
		rec.Published = ""
		err = d.Store.SetCloudHead(rec)
	}
	d.cloudRd.heads.Unlock()
	if err != nil {
		d.Log.Log("warn", fmt.Sprintf("cloud: could not record the restore of %q: %v", game.Name, err))
		return
	}
	d.uploads.Add()
	go func() {
		defer d.uploads.Done()
		_ = d.publishHead(game.ID)
	}()
}

// publishHead announces the game's current save in the cloud.
func (d *Daemon) publishHead(gameID string) error {
	cfg, err := d.Store.GetCloudConfig()
	if err != nil || !cloud.Ready(cfg) || !cloudListable(cfg.Provider) {
		return nil
	}
	game, err := d.Store.GetGame(gameID)
	if err != nil {
		return err
	}
	rec, err := d.cloudHeadFor(game)
	ok := rec.Snapshot != ""
	if err != nil || !ok || rec.Snapshot == "" {
		return err
	}
	if rec.ContentAt <= 0 {
		if d.Log != nil {
			d.Log.Log("warn", fmt.Sprintf("cloud: did not announce %q because its save content time cannot be verified", game.Name))
		}
		return nil
	}
	settings, err := d.Store.GetSettings()
	if err != nil {
		return err
	}
	contentAt := contentAtStamp(rec.ContentAt)
	head := cloud.Head{
		Version:    cloud.HeadVersion,
		DeviceID:   settings.NodeID,
		DeviceName: settings.DeviceName,
		GameID:     game.ID,
		Branch:     game.ActiveBranch,
		Snapshot:   rec.Snapshot,
		File:       rec.File,
		Chain:      rec.Chain,
		// SavedAt carries the same value for older clients that display or rank
		// it; ContentModifiedAt marks it as a trustworthy content timestamp for
		// current clients.
		SavedAt:           contentAt,
		ContentModifiedAt: contentAt,
	}
	if _, err := d.Cloud.PublishHead(head, time.Now()); err != nil {
		if !cloud.IsNotConfigured(err) {
			d.Log.Log("warn", fmt.Sprintf("cloud: could not announce %q's save: %v", game.Name, err))
		}
		return err
	}
	d.cloudRd.heads.Lock()
	defer d.cloudRd.heads.Unlock()
	if rec, _, err := d.Store.GetCloudHead(gameID); err == nil && rec.Snapshot == head.Snapshot {
		rec.Published = head.Snapshot
		_ = d.Store.SetCloudHead(rec)
	}
	return nil
}

// cloudHeadFor returns the game's record, starting one from its newest
// snapshot the first time — a device upgraded from a version without records
// has history but no statement of which snapshot is its save.
func (d *Daemon) cloudHeadFor(game store.Game) (store.CloudHead, error) {
	d.cloudRd.heads.Lock()
	defer d.cloudRd.heads.Unlock()
	rec, ok, err := d.Store.GetCloudHead(game.ID)
	if err != nil {
		return rec, err
	}
	if ok {
		if rec.ContentAt == 0 && rec.Snapshot != "" {
			if snap, getErr := d.Store.GetSnapshot(rec.Snapshot); getErr == nil {
				rec.ContentAt = d.contentAtForSnapshot(snap)
				if rec.ContentAt > 0 {
					if setErr := d.Store.SetCloudHead(rec); setErr != nil {
						return rec, setErr
					}
				}
			}
		}
		return rec, err
	}
	snaps, err := d.Store.ListSnapshots(game.ID, game.ActiveBranch)
	if err != nil || len(snaps) == 0 {
		return rec, err
	}
	latest := snaps[0]
	rec.Snapshot = latest.ID
	rec.Since = latest.Timestamp
	rec.File = cloudSnapshotName(game.ID, latest.BranchName, latest.ID)
	rec.Chain = []string{latest.ID}
	rec.ContentAt = d.contentAtForSnapshot(latest)
	return rec, d.Store.SetCloudHead(rec)
}

// cloudCandidate is another device's head worth acting on.
type cloudCandidate struct {
	head cloud.Head
	file string
	// follows is true when the other save carries on from this device's —
	// this device's save is in its chain — so taking it replaces nothing.
	follows bool
	// contentAt comes from an explicit contentModifiedAt field. Legacy heads
	// lack it and may be shown for confirmation, but never applied on their
	// own. While unresolved, they also keep the local head from being advanced.
	contentAt      int64
	trustedContent bool
}

// chooseCloudCandidate picks, from other devices' heads, the one save this
// device should take or be asked about, if any.
//
// Newer is required in both cases. A save that carries on from ours but is
// older than the moment ours was set — a deliberate rollback here, say — is
// not something to put back over it.
func chooseCloudCandidate(rec store.CloudHead, activeBranch string, heads []cloud.Head,
	have func(snapID string) bool, fileFor func(snapID string) string) (cloudCandidate, bool) {

	var best cloudCandidate
	found := false
	for _, h := range heads {
		if h.Version != cloud.HeadVersion || h.Snapshot == "" || h.Branch != activeBranch {
			continue
		}
		if h.Snapshot == rec.Snapshot || h.Snapshot == rec.Dismissed || have(h.Snapshot) {
			continue
		}
		file := fileFor(h.Snapshot)
		if file == "" {
			continue // announced, but its archive has not arrived (yet)
		}
		contentAt, trustedContent := headContentAt(h)
		if trustedContent && rec.ContentAt > 0 && contentAt < rec.ContentAt {
			continue
		}
		follows := false
		if rec.Snapshot != "" {
			for _, id := range h.Chain {
				if id == rec.Snapshot {
					follows = true
					break
				}
			}
		}
		candidate := cloudCandidate{
			head:           h,
			file:           file,
			follows:        follows,
			contentAt:      contentAt,
			trustedContent: trustedContent,
		}
		if !found || cloudCandidateNewer(candidate, best) {
			best = candidate
			found = true
		}
	}
	return best, found
}

func cloudCandidateNewer(next, current cloudCandidate) bool {
	if next.trustedContent != current.trustedContent {
		return next.trustedContent
	}
	if next.trustedContent && next.contentAt != current.contentAt {
		return next.contentAt > current.contentAt
	}
	return snapMillis(next.head.Snapshot) > snapMillis(current.head.Snapshot)
}

// saveState is how a game's save stands against its last snapshot.
type saveState int

const (
	saveUnchanged saveState = iota
	saveChanged
	saveInUse
	// saveUnknown: nothing recorded to compare with. A game tracked and never
	// changed since has no baseline — the first snapshot does not record one
	// (see the watcher's catch-up for why it must not).
	saveUnknown
)

// saveStateOf says whether the game's save is as its last automatic snapshot
// left it, has changed since, or is open in the game right now.
func (d *Daemon) saveStateOf(game store.Game) saveState {
	if watcher.AnyFileLocked(game.SavePath) {
		return saveInUse
	}
	if game.LastManifestHash == "" {
		return saveUnknown
	}
	hash, err := d.currentContentHash(game)
	if err != nil || hash != game.LastManifestHash {
		return saveChanged
	}
	return saveUnchanged
}

// neverHeldFiles reports whether this device has never had a file of this
// game's save: none on disk now, and none in any snapshot it has taken. The
// snapshots are opened and counted rather than trusted to a record, because
// versions before the file records existed wrote none — a snapshot with no
// record may still have held a save, and a save someone deleted is not a save
// that never was.
func (d *Daemon) neverHeldFiles(game store.Game) bool {
	extra, err := d.Store.GameRootPaths(game.ID)
	if err != nil {
		return false
	}
	m, _, err := delta.BuildMultiManifest(game.SavePath, extra)
	if err != nil || len(m.Files) > 0 {
		return false
	}
	for _, root := range m.Extra {
		if len(root.Files) > 0 {
			return false
		}
	}
	branches, err := d.Store.ListBranches(game.ID)
	if err != nil {
		return false
	}
	for _, b := range branches {
		snaps, err := d.Store.ListSnapshots(game.ID, b)
		if err != nil {
			return false
		}
		for _, sn := range snaps {
			n, err := snapshot.ArchiveFileCount(sn.ZipPath)
			if err != nil || n > 0 {
				return false // unreadable counts as "may have held one"
			}
		}
	}
	return true
}

// currentContentHash reads the save from disk and hashes it the way the
// watcher does when it records a snapshot.
func (d *Daemon) currentContentHash(game store.Game) (string, error) {
	extra, err := d.Store.GameRootPaths(game.ID)
	if err != nil {
		extra = nil
	}
	delta.InvalidateRoot(game.SavePath)
	for _, p := range extra {
		delta.InvalidateRoot(p)
	}
	m, _, err := delta.BuildMultiManifest(game.SavePath, extra)
	if err != nil {
		return "", err
	}
	return watcher.ContentHash(m, game.SyncIgnore), nil
}

// CheckCloud reads the other devices' heads and acts on the ones ahead of
// this device: takes what continues from its save, asks about the rest.
func (d *Daemon) CheckCloud() {
	// Paused means nothing comes from the cloud either; resuming checks.
	if d.P2P.Pause.Paused() {
		return
	}
	d.cloudRd.check.Lock()
	defer d.cloudRd.check.Unlock()

	cfg, err := d.Store.GetCloudConfig()
	if err != nil || !cloud.Ready(cfg) || !cloudListable(cfg.Provider) {
		d.setCloudOffers(nil)
		return
	}
	files, err := d.Cloud.List()
	if err != nil {
		if cloud.IsNotConfigured(err) {
			d.setCloudOffers(nil)
		} else {
			d.Log.Log("warn", fmt.Sprintf("cloud: could not check for newer saves: %v", err))
		}
		return
	}
	// The listing worked, so the provider can be reached: send what failed to
	// go up before. Then list again, so what is read below includes it.
	if pending, _ := d.Store.CloudRetries(); len(pending) > 0 {
		d.retryFailedUploads(files)
		if again, err := d.Cloud.List(); err == nil {
			files = again
		}
	}
	settings, err := d.Store.GetSettings()
	if err != nil {
		return
	}
	ownKey := cloud.DeviceKey(settings.NodeID)

	// EDEN_SYNCD uses a separate remote namespace for each save type. Keep it
	// in every lookup key so a PC archive cannot satisfy a Switch head with the
	// same game id or snapshot id. Other providers remain flat as before.
	scope := func(saveType, name string) string {
		return cloud.ScopedName(cfg.Provider, saveType, name)
	}
	snapFiles := map[string]string{}                  // type/game id/branch/snapshot id -> file
	newest := map[string]map[string]cloud.CloudFile{} // type/game id -> device -> head
	newestAt := map[string]int64{}                    // type/game id/device -> ms
	ownHeads := map[string][]cloud.CloudFile{}        // type/game id -> this device's heads
	for _, f := range files {
		if gameID, dev, at, ok := cloud.ParseHeadFileName(f.Name); ok {
			gameKey := scope(f.ID, gameID)
			if dev == ownKey {
				ownHeads[gameKey] = append(ownHeads[gameKey], f)
				continue
			}
			key := gameKey + "/" + dev
			if at > newestAt[key] {
				newestAt[key] = at
				if newest[gameKey] == nil {
					newest[gameKey] = map[string]cloud.CloudFile{}
				}
				newest[gameKey][dev] = f
			}
			continue
		}
		if gameID, branch, snapID, ok := snapshot.ParseExportEntryName(f.Name); ok {
			snapFiles[scope(f.ID, gameID+"/"+branch+"/"+snapID)] = f.Name
		}
	}

	games, err := d.Store.ListGames()
	if err != nil {
		return
	}
	var offers []CloudOffer
	for _, game := range games {
		gameKey := scope(game.SaveType, game.ID)
		d.pruneOwnHeads(ownHeads[gameKey])
		rec, err := d.cloudHeadFor(game)
		if err != nil {
			continue
		}
		ids, err := d.Store.LinkedGameIDs(game.ID)
		if err != nil || len(ids) == 0 {
			ids = []string{game.ID}
		}
		// Found by game, branch and snapshot, never by snapshot alone. Ids are
		// milliseconds, so two devices snapshotting two different games in the
		// same one share an id — and looking it up by id alone could hand this
		// game the other one's archive to restore.
		fileFor := func(snapID string) string {
			for _, id := range ids {
				if f := snapFiles[scope(game.SaveType, id+"/"+game.ActiveBranch+"/"+snapID)]; f != "" {
					return f
				}
			}
			return ""
		}

		var heads []cloud.Head
		for _, id := range ids {
			for _, f := range newest[scope(game.SaveType, id)] {
				if h, err := d.readHead(f); err == nil {
					heads = append(heads, h)
				}
			}
		}
		// This game's, for the same reason: another game's snapshot with the
		// same id says nothing about whether this one has it.
		have := func(snapID string) bool {
			snap, err := d.Store.GetSnapshot(snapID)
			return err == nil && snap.GameID == game.ID
		}
		cand, ok := chooseCloudCandidate(rec, game.ActiveBranch, heads, have, fileFor)
		if !ok {
			// Only announce the local save after every remote head has been
			// checked. Publishing first lets an old machine make a stale copy
			// look current while a newer remote save is waiting to be handled.
			if rec.Snapshot != "" && fileFor(rec.Snapshot) != "" &&
				(rec.Published != rec.Snapshot || len(ownHeads[gameKey]) == 0) {
				_ = d.publishHead(game.ID)
			}
			continue
		}

		// A save that has never held a file on this device — the folder was
		// empty when it was tracked and has been since — has nothing to lose,
		// whatever the other device's line of saves. That is a new device
		// being set up, the case the cloud is best at, and it is filled
		// without asking. One that held files and is empty now is a deletion
		// somebody made, and that is still asked about.
		state := d.saveStateOf(game)
		if state == saveInUse {
			continue // the game is running; not now, and not a question either
		}
		follows := cand.follows
		emptyNewDevice := false
		if !follows && d.neverHeldFiles(game) {
			follows, state, emptyNewDevice = true, saveUnchanged, true
		}
		safeContinuation := cand.trustedContent && (emptyNewDevice ||
			(follows && rec.ContentAt > 0 && cand.contentAt >= rec.ContentAt))
		if safeContinuation && state == saveUnchanged && settings.CloudAutoPull && game.AutoSync {
			err := d.pullFromCloud(game, cand.file, cand.head.DeviceName)
			if err == nil {
				if d.OnCloudPulled != nil {
					d.OnCloudPulled(CloudPulled{GameID: game.ID, GameName: game.Name,
						DeviceName: cand.head.DeviceName, SnapshotID: cand.head.Snapshot})
				}
				continue
			}
			d.Log.Log("warn", fmt.Sprintf("cloud: could not bring %s's save for %q here: %v",
				cand.head.DeviceName, game.Name, err))
		}
		// Asked. Marked as replacing progress when it would, and whenever a
		// legacy or damaged announcement does not prove the content timestamp.
		// That latter case could otherwise be an old save backed up late.
		//
		// Marked as replacing progress only when it would: the other
		// save does not carry on from this one, or this one has changed since.
		// Not when this device's state is merely unknown — a game tracked and
		// never changed has no recorded baseline, and telling that person
		// their device "has progress of its own" would be untrue.
		diverged := !follows || state == saveChanged || !cand.trustedContent || rec.ContentAt == 0
		offers = append(offers, CloudOffer{
			GameID:       game.ID,
			GameName:     game.Name,
			SnapshotID:   cand.head.Snapshot,
			FileName:     cand.file,
			DeviceName:   cand.head.DeviceName,
			SavedAt:      cand.head.SavedAt,
			Provider:     cfg.Provider,
			ReviewReason: cloudReviewReason(cand, follows, state, rec),
			Diverged:     diverged,
		})
	}
	d.setCloudOffers(offers)
}

func cloudReviewReason(cand cloudCandidate, follows bool, state saveState, rec store.CloudHead) string {
	switch {
	case !cand.trustedContent:
		return "legacy-content-time"
	case rec.ContentAt == 0:
		return "local-content-time-unknown"
	case state == saveChanged:
		return "local-changed"
	case state == saveUnknown:
		return "local-baseline-unknown"
	case !follows:
		return "history-diverged"
	default:
		return ""
	}
}

// readHead fetches a head, from the cache when it has been read before.
func (d *Daemon) readHead(file cloud.CloudFile) (cloud.Head, error) {
	// Names are only unique inside EDEN_SYNCD's save-type namespace. Keeping
	// the type in the cache key prevents a PC head being reused for a Switch
	// head with the same game, device, and timestamp.
	cacheKey := file.ID + "\x00" + file.Name
	d.cloudRd.cacheMu.Lock()
	h, ok := d.cloudRd.cache[cacheKey]
	d.cloudRd.cacheMu.Unlock()
	if ok {
		return h, nil
	}
	h, err := d.Cloud.FetchHeadFile(file)
	if err != nil {
		return cloud.Head{}, err
	}
	d.cloudRd.cacheMu.Lock()
	if d.cloudRd.cache == nil {
		d.cloudRd.cache = map[string]cloud.Head{}
	}
	d.cloudRd.cache[cacheKey] = h
	d.cloudRd.cacheMu.Unlock()
	return h, nil
}

// pruneOwnHeads removes this device's older announcements for one game; only
// the newest means anything, and without this every save would leave one
// behind for good.
func (d *Daemon) pruneOwnHeads(files []cloud.CloudFile) {
	if len(files) < 2 {
		return
	}
	sort.Slice(files, func(i, j int) bool {
		_, _, a, _ := cloud.ParseHeadFileName(files[i].Name)
		_, _, b, _ := cloud.ParseHeadFileName(files[j].Name)
		return a > b
	})
	for _, f := range files[1:] {
		_ = d.Cloud.Delete(f)
	}
}

// pullFromCloud puts another device's cloud snapshot in place as this
// device's save. The save it replaces is kept first, as every restore keeps
// it.
func (d *Daemon) pullFromCloud(game store.Game, fileName, deviceName string) error {
	_, branch, snapID, ok := snapshot.ParseExportEntryName(fileName)
	if !ok {
		return fmt.Errorf("%q is not a snapshot", fileName)
	}
	settings, err := d.Store.GetSettings()
	if err != nil {
		return err
	}
	destDir := filepath.Join(settings.BackupsDir, game.ID, branch)
	if err := os.MkdirAll(destDir, 0o777); err != nil {
		return err
	}
	destPath := filepath.Join(destDir, snapID+".zip")
	if err := d.Cloud.DownloadFile(cloud.CloudFile{ID: cloud.SaveTypeScope(game.SaveType), Name: fileName}, destPath); err != nil {
		return err
	}
	info, err := os.Stat(destPath)
	if err != nil {
		return err
	}
	if err := d.EnsureImportedSnapshot(game.ID, branch, snapID, destPath, info.Size()); err != nil {
		return err
	}
	// Another device's save: the files this device keeps for itself stay
	// its own. See Manager.RestoreKeeping.
	if _, err := d.Snapshots.RestoreKeeping(game.ID, snapID, ignore.Parse(game.SyncIgnore)); err != nil {
		return err
	}
	// What is on disk now is that snapshot, so record it as the baseline.
	// Otherwise the watcher sees the restore as the game writing new data,
	// snapshots it again, and the copy goes back up as if it were new.
	if hash, err := d.currentContentHash(game); err == nil {
		_ = d.Store.SetLastManifestHash(game.ID, hash)
	}
	if err := d.noteCloudPullForCloud(game, snapID, fileName, destPath); err != nil {
		return err
	}
	d.Log.Log("success", fmt.Sprintf("cloud: brought %s's save for %q here", deviceName, game.Name))
	d.P2P.Sync.RecordActivity(store.ActivityEvent{GameID: game.ID, Kind: store.ActivityCloudPulled, Device: deviceName})
	if d.OnGameChanged != nil {
		d.OnGameChanged(game.ID)
	}
	return nil
}

// noteCloudPullForCloud makes a remotely restored snapshot the local head.
// RestoreKeeping intentionally does not trigger Snapshot.Manager's restore
// hook, so without this update a machine would keep publishing the snapshot it
// had before the pull. That stale announcement was able to later overwrite a
// newer save on another device.
func (d *Daemon) noteCloudPullForCloud(game store.Game, snapshotID, fileName, zipPath string) error {
	contentAt, err := snapshotContentModifiedAt(zipPath)
	if err != nil {
		contentAt = 0
		if d.Log != nil {
			d.Log.Log("warn", fmt.Sprintf("cloud: restored %q but could not verify its content time: %v", game.Name, err))
		}
	}

	d.cloudRd.heads.Lock()
	rec, _, err := d.Store.GetCloudHead(game.ID)
	if err == nil {
		rec.Snapshot = snapshotID
		rec.Since = nowStamp()
		rec.File = fileName
		rec.Chain = []string{snapshotID}
		rec.ContentAt = contentAt
		rec.Published = ""
		rec.Dismissed = ""
		err = d.Store.SetCloudHead(rec)
	}
	d.cloudRd.heads.Unlock()
	if err != nil {
		return fmt.Errorf("record restored cloud save: %w", err)
	}

	d.uploads.Add()
	go func() {
		defer d.uploads.Done()
		_ = d.publishHead(game.ID)
	}()
	return nil
}

// AcceptCloudOffer takes the offered save.
func (d *Daemon) AcceptCloudOffer(gameID, snapshotID string) error {
	d.cloudRd.check.Lock()
	defer d.cloudRd.check.Unlock()
	offer, ok := d.findCloudOffer(gameID, snapshotID)
	if !ok {
		return ErrCloudOfferGone
	}
	game, err := d.Store.GetGame(gameID)
	if err != nil {
		return err
	}
	if watcher.AnyFileLocked(game.SavePath) {
		return fmt.Errorf("%s has its save files open — close the game, then try again", game.Name)
	}
	if err := d.pullFromCloud(game, offer.FileName, offer.DeviceName); err != nil {
		return err
	}
	d.dropCloudOffers(gameID)
	return nil
}

// DismissCloudOffer says no to the offered save. A newer one is offered again.
func (d *Daemon) DismissCloudOffer(gameID, snapshotID string) error {
	d.cloudRd.heads.Lock()
	rec, _, err := d.Store.GetCloudHead(gameID)
	if err == nil {
		rec.Dismissed = snapshotID
		err = d.Store.SetCloudHead(rec)
	}
	d.cloudRd.heads.Unlock()
	if err != nil {
		return err
	}
	d.dropCloudOffers(gameID)
	return nil
}

// CloudOffers returns the saves waiting for an answer.
func (d *Daemon) CloudOffers() []CloudOffer {
	d.cloudRd.offersMu.Lock()
	defer d.cloudRd.offersMu.Unlock()
	return append([]CloudOffer{}, d.cloudRd.offers...)
}

func (d *Daemon) findCloudOffer(gameID, snapshotID string) (CloudOffer, bool) {
	for _, o := range d.CloudOffers() {
		if o.GameID == gameID && o.SnapshotID == snapshotID {
			return o, true
		}
	}
	return CloudOffer{}, false
}

// dropCloudOffers removes a game's offer, after it has been answered or the
// save restored some other way.
func (d *Daemon) dropCloudOffers(gameID string) {
	var kept []CloudOffer
	for _, o := range d.CloudOffers() {
		if o.GameID != gameID {
			kept = append(kept, o)
		}
	}
	d.setCloudOffers(kept)
}

// setCloudOffers replaces the open offers, telling listeners — and the log,
// which is all a headless install has — about the ones that are new.
func (d *Daemon) setCloudOffers(offers []CloudOffer) {
	d.cloudRd.offersMu.Lock()
	before := map[string]bool{}
	for _, o := range d.cloudRd.offers {
		before[o.GameID+"/"+o.SnapshotID] = true
	}
	changed := len(before) != len(offers)
	for _, o := range offers {
		if !before[o.GameID+"/"+o.SnapshotID] {
			changed = true
			d.Log.Log("info", fmt.Sprintf("cloud: %s has a newer save for %q — open OpenSave to bring it here", o.DeviceName, o.GameName))
		}
	}
	d.cloudRd.offers = append([]CloudOffer{}, offers...)
	d.cloudRd.offersMu.Unlock()
	if changed && d.OnCloudOffers != nil {
		d.OnCloudOffers(d.CloudOffers())
	}
}

// ForgetCloudOffers drops a game's offer after its save was restored by some
// other route — the cloud screen — so the banner does not keep asking about
// a question already answered.
func (d *Daemon) ForgetCloudOffers(gameID string) { d.dropCloudOffers(gameID) }
