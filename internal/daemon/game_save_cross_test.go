package daemon

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/snapshot"
	"github.com/opensave/opensave/internal/store"
)

const crossRestoreTestToken = "cross-restore-test-token"

type crossRestoreTestRequest struct {
	Method  string
	Path    string
	IfMatch string
}

// crossRestoreTestServer is a small in-memory game-save service. It records
// every request so a test can prove that a cross-device restore reads the
// source scope and never writes to it.
type crossRestoreTestServer struct {
	mu            sync.Mutex
	archives      map[string][]byte
	scopes        map[string]*crossRestoreTestScope
	requests      []crossRestoreTestRequest
	uploadGate    chan struct{}
	uploadReached chan struct{}
	server        *httptest.Server
}

type crossRestoreTestScope struct {
	head      string
	snapshots map[string]cloud.GameSaveScopeSnapshot
}

func newCrossRestoreTestServer(t *testing.T) *crossRestoreTestServer {
	t.Helper()
	s := &crossRestoreTestServer{
		archives: map[string][]byte{},
		scopes:   map[string]*crossRestoreTestScope{},
	}
	s.server = httptest.NewServer(http.HandlerFunc(s.serveHTTP))
	t.Cleanup(s.server.Close)
	return s
}

func (s *crossRestoreTestServer) url() string { return s.server.URL }

func (s *crossRestoreTestServer) serveHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer "+crossRestoreTestToken {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	s.mu.Lock()
	s.requests = append(s.requests, crossRestoreTestRequest{
		Method:  r.Method,
		Path:    r.URL.Path,
		IfMatch: r.Header.Get("If-Match"),
	})
	s.mu.Unlock()

	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) >= 3 && parts[0] == "v1" && parts[1] == "opensave" {
		switch parts[2] {
		case "files":
			s.handleFiles(w, r, parts)
			return
		case "scopes":
			s.handleScopes(w, r, parts)
			return
		}
	}
	w.WriteHeader(http.StatusNotFound)
}

func (s *crossRestoreTestServer) handleFiles(w http.ResponseWriter, r *http.Request, parts []string) {
	if len(parts) != 5 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	key := parts[3] + "/" + parts[4]
	switch r.Method {
	case http.MethodGet:
		s.mu.Lock()
		body, exists := s.archives[key]
		s.mu.Unlock()
		if !exists {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write(body)
	case http.MethodPut:
		body, err := io.ReadAll(r.Body)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		gate, reached := s.uploadGate, s.uploadReached
		s.mu.Unlock()
		if gate != nil {
			if reached != nil {
				select {
				case reached <- struct{}{}:
				default:
				}
			}
			<-gate
		}
		s.mu.Lock()
		previous, exists := s.archives[key]
		if !exists {
			s.archives[key] = append([]byte(nil), body...)
		}
		s.mu.Unlock()
		if exists && !bytes.Equal(previous, body) {
			w.WriteHeader(http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusCreated)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (s *crossRestoreTestServer) handleScopes(w http.ResponseWriter, r *http.Request, parts []string) {
	if len(parts) == 5 && r.Method == http.MethodGet {
		s.mu.Lock()
		response := s.scopeResponseLocked(parts[3], parts[4])
		s.mu.Unlock()
		writeNativeGameSaveJSON(w, http.StatusOK, response)
		return
	}
	if len(parts) != 7 || r.Method != http.MethodPut || (parts[5] != "snapshots" && parts[5] != "conflicts") {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	kind, gameID, operation, snapshotID := parts[3], parts[4], parts[5], parts[6]
	var candidate cloud.GameSaveScopeCandidate
	if err := json.NewDecoder(r.Body).Decode(&candidate); err != nil || candidate.ID != snapshotID {
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.scopeLocked(kind, gameID)
	if operation == "snapshots" {
		expected := strings.Trim(r.Header.Get("If-Match"), "\"")
		if expected != state.head || candidate.Parent != state.head {
			writeNativeGameSaveJSON(w, http.StatusConflict, map[string]string{"head": state.head})
			return
		}
		if err := s.validateArchiveLocked(kind, candidate); err != nil {
			writeNativeGameSaveJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		stored := cloud.GameSaveScopeSnapshot{
			GameSaveScopeCandidate: candidate,
			Device:                 r.Header.Get("X-OpenSave-Device-ID"),
			DeviceName:             r.Header.Get("X-OpenSave-Device-Name"),
			Platform:               r.Header.Get("X-OpenSave-Platform"),
			ReceivedAt:             time.Now().UTC(),
		}
		state.snapshots[candidate.ID] = stored
		state.head = candidate.ID
		w.Header().Set("ETag", `"`+candidate.ID+`"`)
		writeNativeGameSaveJSON(w, http.StatusCreated, stored)
		return
	}

	if state.head == "" {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	if err := s.validateArchiveLocked(kind, candidate); err != nil {
		writeNativeGameSaveJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	stored := cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: candidate,
		Device:                 r.Header.Get("X-OpenSave-Device-ID"),
		DeviceName:             r.Header.Get("X-OpenSave-Device-Name"),
		Platform:               r.Header.Get("X-OpenSave-Platform"),
		ReceivedAt:             time.Now().UTC(),
		Conflict:               true,
		ConflictOf:             state.head,
	}
	state.snapshots[candidate.ID] = stored
	writeNativeGameSaveJSON(w, http.StatusCreated, stored)
}

func (s *crossRestoreTestServer) validateArchiveLocked(kind string, candidate cloud.GameSaveScopeCandidate) error {
	archive, exists := s.archives[kind+"/"+candidate.Archive]
	if !exists {
		return io.EOF
	}
	digest := sha256.Sum256(archive)
	if !strings.EqualFold(candidate.ContentDigest, hex.EncodeToString(digest[:])) {
		return io.ErrUnexpectedEOF
	}
	return nil
}

func (s *crossRestoreTestServer) scopeLocked(kind, gameID string) *crossRestoreTestScope {
	key := kind + "/" + gameID
	if state := s.scopes[key]; state != nil {
		return state
	}
	state := &crossRestoreTestScope{snapshots: map[string]cloud.GameSaveScopeSnapshot{}}
	s.scopes[key] = state
	return state
}

func (s *crossRestoreTestServer) scopeResponseLocked(kind, gameID string) cloud.GameSaveScope {
	state := s.scopeLocked(kind, gameID)
	response := cloud.GameSaveScope{Type: kind, GameID: gameID}
	if state.head != "" {
		head := state.snapshots[state.head]
		response.Head = &head
	}
	for _, item := range state.snapshots {
		response.Snapshots = append(response.Snapshots, item)
	}
	return response
}

func (s *crossRestoreTestServer) addArchive(kind, name string, data []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.archives[kind+"/"+name] = append([]byte(nil), data...)
}

func (s *crossRestoreTestServer) addSnapshot(kind, gameID string, snap cloud.GameSaveScopeSnapshot, conflict bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.scopeLocked(kind, gameID)
	snap.Conflict = conflict
	state.snapshots[snap.ID] = snap
	if !conflict {
		state.head = snap.ID
	}
}

func (s *crossRestoreTestServer) scope(kind, gameID string) cloud.GameSaveScope {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.scopeResponseLocked(kind, gameID)
}

func (s *crossRestoreTestServer) recordedRequests() []crossRestoreTestRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]crossRestoreTestRequest(nil), s.requests...)
}

func (s *crossRestoreTestServer) clearRequests() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.requests = nil
}

// blockUploads holds every archive PUT until the returned release is called.
// A test can then look at the local cloud head while the ordinary upload is
// still in flight, before that upload legitimately advances it.
func (s *crossRestoreTestServer) blockUploads() (<-chan struct{}, func()) {
	gate := make(chan struct{})
	reached := make(chan struct{}, 1)
	s.mu.Lock()
	s.uploadGate, s.uploadReached = gate, reached
	s.mu.Unlock()
	var once sync.Once
	return reached, func() { once.Do(func() { close(gate) }) }
}

type crossRestoreDevice struct {
	t      *testing.T
	d      *Daemon
	save   string
	nodeID string
}

func newCrossRestoreDevice(t *testing.T, name, nodeID, serviceURL, saveType string) *crossRestoreDevice {
	t.Helper()
	d := newTestDaemon(t)
	settings, err := d.Store.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	settings.DeviceName = name
	settings.CloudAutoPull = true
	if err := d.Store.UpdateSettings(settings); err != nil {
		t.Fatal(err)
	}
	stored, err := d.Store.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := d.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	cfg.Enabled = true
	cfg.Provider = cloud.GameSaveProvider
	cfg.URL = serviceURL
	cfg.Password = crossRestoreTestToken
	if err := d.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}
	save := t.TempDir()
	if err := d.Store.CreateGame(store.Game{
		ID: cloudGame, Name: "Hollow Knight", SavePath: save, ActiveBranch: "main",
		AutoSync: true, MaxSnapshots: 50, SaveType: saveType,
	}); err != nil {
		t.Fatal(err)
	}
	return &crossRestoreDevice{t: t, d: d, save: save, nodeID: stored.NodeID}
}

func (c *crossRestoreDevice) play(content string) store.Snapshot {
	c.t.Helper()
	// Snapshot ids are milliseconds; leave room for a safety copy to follow.
	time.Sleep(5 * time.Millisecond)
	if err := os.WriteFile(filepath.Join(c.save, "user1.dat"), []byte(content), 0o666); err != nil {
		c.t.Fatal(err)
	}
	snap, err := c.d.Snapshots.Create(cloudGame, "", true)
	if err != nil {
		c.t.Fatal(err)
	}
	game, err := c.d.Store.GetGame(cloudGame)
	if err != nil {
		c.t.Fatal(err)
	}
	hash, err := c.d.currentContentHash(game)
	if err != nil {
		c.t.Fatal(err)
	}
	if err := c.d.Store.SetLastManifestHash(cloudGame, hash); err != nil {
		c.t.Fatal(err)
	}
	if !c.d.uploads.Wait(5 * time.Second) {
		c.t.Fatal("native game-save upload did not finish")
	}
	return snap
}

func writeCrossRestoreZip(t *testing.T, zipPath string, entries map[string]string) {
	t.Helper()
	f, err := os.Create(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	w := zip.NewWriter(f)
	names := make([]string, 0, len(entries))
	for name := range entries {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		entry, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte(entries[name])); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

func mustReadCrossRestoreFile(t *testing.T, path string) []byte {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func readCrossRestoreZip(t *testing.T, zipPath string) map[string]string {
	t.Helper()
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	out := map[string]string{}
	for _, f := range r.File {
		if f.FileInfo().IsDir() {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			t.Fatal(err)
		}
		raw, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatal(err)
		}
		out[f.Name] = string(raw)
	}
	return out
}

func crossRestoreTree(t *testing.T, root string) map[string]string {
	t.Helper()
	out := map[string]string{}
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		raw, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		out[filepath.ToSlash(rel)] = string(raw)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func sameCrossRestoreTree(t *testing.T, want, got map[string]string) {
	t.Helper()
	if len(want) != len(got) {
		t.Fatalf("files changed: want %v, got %v", want, got)
	}
	for name, content := range want {
		if got[name] != content {
			t.Fatalf("file %q changed: want %q, got %q", name, content, got[name])
		}
	}
}

const crossRestoreAndroidManifest = `{"version":1,"gameKey":"switch/title:0100000000010000","gameName":"Super Mario Odyssey","locations":[{"kind":"switch-title","relativePath":"a/b","archivePath":"location-0000","candidate":false},{"kind":"android-game","relativePath":"c","archivePath":"location-0001","candidate":false}]}`

func crossRestorePCData(t *testing.T) []byte {
	t.Helper()
	path := filepath.Join(t.TempDir(), "pc.zip")
	writeCrossRestoreZip(t, path, map[string]string{
		"slot1.sav": "cloud save",
		"cloud.dat": "cloud extra",
	})
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func crossRestoreAndroidData(t *testing.T) []byte {
	t.Helper()
	path := filepath.Join(t.TempDir(), "android.zip")
	writeCrossRestoreZip(t, path, map[string]string{
		"opensave-game.json":      crossRestoreAndroidManifest,
		"location-0000/slot.sav":  "android save",
		"location-0001/other.dat": "other location",
	})
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func crossRestoreSourceSnapshot(archiveName string, data []byte, deviceName, platform string) cloud.GameSaveScopeSnapshot {
	digest := sha256.Sum256(data)
	return cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
			ID:            "snap_1800000000000--dev-source",
			Archive:       archiveName,
			ContentDigest: hex.EncodeToString(digest[:]),
			CreatedAt:     time.Now().UTC(),
		},
		DeviceName: deviceName,
		Platform:   platform,
		ReceivedAt: time.Now().UTC(),
	}
}

func TestCrossRestorePlanPCRootSingleLocation(t *testing.T) {
	service := newCrossRestoreTestServer(t)
	device := newCrossRestoreDevice(t, "Desktop", "desktop-node", service.url(), "pc")
	archiveName := "hollow-knight__main__snap_1800000000000--dev-source.zip"
	data := crossRestorePCData(t)
	service.addArchive("pc", archiveName, data)
	source := crossRestoreSourceSnapshot(archiveName, data, "Steam Deck", "linux")
	service.addSnapshot("pc", "android-pc-ab12", source, false)

	before := crossRestoreTree(t, device.save)
	snapsBefore, err := device.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}

	plan, err := device.d.PlanCrossDeviceRestore(cloudGame, "android-pc-ab12", source.ID)
	if err != nil {
		t.Fatal(err)
	}
	if plan.Format != snapshot.ForeignFormatPC || plan.SourceGameName != "" {
		t.Fatalf("plan format = %+v", plan)
	}
	if plan.DeviceName != "Steam Deck" || plan.Platform != "linux" || plan.MemberGameID != "android-pc-ab12" {
		t.Fatalf("plan source = %+v", plan)
	}
	if len(plan.ArchiveLocations) != 1 || plan.ArchiveLocations[0].ID != snapshot.ForeignPCRoot ||
		plan.ArchiveLocations[0].Files != 2 {
		t.Fatalf("archive locations = %+v", plan.ArchiveLocations)
	}
	if len(plan.LocalLocations) != 1 || plan.LocalLocations[0].Root != "" ||
		plan.LocalLocations[0].Kind != "gamehub-pc" {
		t.Fatalf("local locations = %+v", plan.LocalLocations)
	}
	if len(plan.Suggested) != 1 ||
		plan.Suggested[0].ArchiveLocation != snapshot.ForeignPCRoot ||
		plan.Suggested[0].LocalRoot != "" {
		t.Fatalf("suggested = %+v", plan.Suggested)
	}
	if plan.IgnoredEntries != 0 {
		t.Fatalf("ignored entries = %d", plan.IgnoredEntries)
	}

	sameCrossRestoreTree(t, before, crossRestoreTree(t, device.save))
	snapsAfter, err := device.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(snapsAfter) != len(snapsBefore) {
		t.Fatalf("plan created snapshots: before %d, after %d", len(snapsBefore), len(snapsAfter))
	}
	for _, request := range service.recordedRequests() {
		if request.Method != http.MethodGet {
			t.Fatalf("plan sent a %s to %s", request.Method, request.Path)
		}
	}
}

func TestCrossRestorePlanPCWithTwoLocalLocationsSuggestsNothing(t *testing.T) {
	service := newCrossRestoreTestServer(t)
	device := newCrossRestoreDevice(t, "Desktop", "desktop-node", service.url(), "pc")
	extra := filepath.Join(t.TempDir(), "config")
	if err := os.MkdirAll(extra, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := device.d.Store.AddGameRoot(cloudGame, "config", extra); err != nil {
		t.Fatal(err)
	}
	archiveName := "hollow-knight__main__snap_1800000000000--dev-source.zip"
	data := crossRestorePCData(t)
	service.addArchive("pc", archiveName, data)
	source := crossRestoreSourceSnapshot(archiveName, data, "Steam Deck", "linux")
	service.addSnapshot("pc", "android-pc-ab12", source, false)

	plan, err := device.d.PlanCrossDeviceRestore(cloudGame, "android-pc-ab12", source.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.LocalLocations) != 2 {
		t.Fatalf("local locations = %+v", plan.LocalLocations)
	}
	if plan.Suggested == nil || len(plan.Suggested) != 0 {
		t.Fatalf("suggested = %+v, want an empty non-nil list", plan.Suggested)
	}
}

func TestCrossRestorePlanAndroidSuggestsMatchingKindOnly(t *testing.T) {
	service := newCrossRestoreTestServer(t)
	device := newCrossRestoreDevice(t, "Desktop", "desktop-node", service.url(), "switch")
	extra := filepath.Join(t.TempDir(), "config")
	if err := os.MkdirAll(extra, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := device.d.Store.AddGameRoot(cloudGame, "config", extra); err != nil {
		t.Fatal(err)
	}
	archiveName := "hollow-knight__main__snap_1800000000000--dev-source.zip"
	data := crossRestoreAndroidData(t)
	service.addArchive("switch", archiveName, data)
	source := crossRestoreSourceSnapshot(archiveName, data, "Pixel 8", "android")
	service.addSnapshot("switch", "android-switch-ab12", source, false)

	before := crossRestoreTree(t, device.save)
	plan, err := device.d.PlanCrossDeviceRestore(cloudGame, "android-switch-ab12", source.ID)
	if err != nil {
		t.Fatal(err)
	}
	if plan.Format != snapshot.ForeignFormatAndroid || plan.SourceGameName != "Super Mario Odyssey" {
		t.Fatalf("plan = %+v", plan)
	}
	if len(plan.ArchiveLocations) != 2 ||
		plan.ArchiveLocations[0].ID != "location-0000" || plan.ArchiveLocations[0].Kind != "switch-title" ||
		plan.ArchiveLocations[1].ID != "location-0001" || plan.ArchiveLocations[1].Kind != "android-game" {
		t.Fatalf("archive locations = %+v", plan.ArchiveLocations)
	}
	if len(plan.LocalLocations) != 2 || plan.LocalLocations[0].Kind != "switch-title" ||
		plan.LocalLocations[1].Root != "config" || plan.LocalLocations[1].Kind != "" {
		t.Fatalf("local locations = %+v", plan.LocalLocations)
	}
	if len(plan.Suggested) != 1 ||
		plan.Suggested[0].ArchiveLocation != "location-0000" ||
		plan.Suggested[0].LocalRoot != "" {
		t.Fatalf("suggested = %+v, want only the switch-title pair", plan.Suggested)
	}
	sameCrossRestoreTree(t, before, crossRestoreTree(t, device.save))
	for _, request := range service.recordedRequests() {
		if request.Method != http.MethodGet {
			t.Fatalf("plan sent a %s to %s", request.Method, request.Path)
		}
	}
}

func TestCrossRestoreApplyPCPublishesChildOfLocalHead(t *testing.T) {
	service := newCrossRestoreTestServer(t)
	device := newCrossRestoreDevice(t, "Desktop", "desktop-node", service.url(), "pc")
	device.play("base")
	service.clearRequests()
	preHead := service.scope("pc", cloudGame).Head
	if preHead == nil || preHead.ID == "" {
		t.Fatal("local scope has no head to publish under")
	}
	preHeadID := preHead.ID

	archiveName := "hollow-knight__main__snap_1800000000000--dev-source.zip"
	data := crossRestorePCData(t)
	service.addArchive("pc", archiveName, data)
	source := crossRestoreSourceSnapshot(archiveName, data, "Steam Deck", "linux")
	service.addSnapshot("pc", "android-pc-ab12", source, false)

	reached, release := service.blockUploads()
	defer release()
	changed := ""
	device.d.OnGameChanged = func(gameID string) { changed = gameID }

	result, err := device.d.ApplyCrossDeviceRestore(cloudGame, "android-pc-ab12", source.ID,
		[]snapshot.ForeignAssignment{{ArchiveLocation: snapshot.ForeignPCRoot, LocalRoot: ""}})
	if err != nil {
		t.Fatal(err)
	}
	if result.SafetySnapshotID == "" || result.RestoredSnapshotID == "" {
		t.Fatalf("result = %+v", result)
	}
	if changed != cloudGame {
		t.Fatalf("OnGameChanged = %q", changed)
	}
	if got := string(mustReadCrossRestoreFile(t, filepath.Join(device.save, "slot1.sav"))); got != "cloud save" {
		t.Fatalf("restored save = %q", got)
	}
	if _, err := os.Stat(filepath.Join(device.save, "user1.dat")); !os.IsNotExist(err) {
		t.Fatalf("the old save was not replaced: %v", err)
	}

	snaps, err := device.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(snaps) != 3 {
		t.Fatalf("snapshots = %+v, want base + safety + restored", snaps)
	}
	if snaps[0].ID != result.RestoredSnapshotID || snaps[1].ID != result.SafetySnapshotID {
		t.Fatalf("snapshot order = %+v", snaps)
	}
	if snaps[0].Comment != "Restored from Steam Deck" {
		t.Fatalf("restored comment = %q", snaps[0].Comment)
	}
	if snaps[1].Comment != "Safety copy before restoring a save from Steam Deck" || !snaps[1].IsSystemAuto {
		t.Fatalf("safety snapshot = %+v", snaps[1])
	}
	safetyFiles := readCrossRestoreZip(t, snaps[1].ZipPath)
	if safetyFiles["user1.dat"] != "base" {
		t.Fatalf("safety archive = %+v, want the pre-restore save", safetyFiles)
	}

	// The restore itself must not touch this device's confirmed head. The
	// ordinary upload that follows is what may advance it.
	record, ok, err := device.d.Store.GetCloudHead(cloudGame)
	if err != nil || !ok {
		t.Fatalf("cloud head = (%+v, %v, %v)", record, ok, err)
	}
	if record.NativeHead != preHeadID {
		t.Fatalf("NativeHead = %q before upload, want %q", record.NativeHead, preHeadID)
	}
	select {
	case <-reached:
	case <-time.After(5 * time.Second):
		t.Fatal("the restored snapshot was never uploaded")
	}
	if got := service.scope("pc", cloudGame).Head; got == nil || got.ID != preHeadID {
		t.Fatalf("local scope head moved while the upload was blocked: %+v", got)
	}
	record, _, _ = device.d.Store.GetCloudHead(cloudGame)
	if record.NativeHead != preHeadID {
		t.Fatalf("NativeHead = %q while the upload was blocked, want %q", record.NativeHead, preHeadID)
	}

	release()
	if !device.d.uploads.Wait(5 * time.Second) {
		t.Fatal("restored snapshot upload did not finish")
	}
	localScope := service.scope("pc", cloudGame)
	if localScope.Head == nil || localScope.Head.ID == preHeadID || localScope.Head.Parent != preHeadID {
		t.Fatalf("local scope after upload = %+v, want a child of %q", localScope.Head, preHeadID)
	}
	record, _, _ = device.d.Store.GetCloudHead(cloudGame)
	if record.NativeHead != localScope.Head.ID {
		t.Fatalf("NativeHead = %q after upload, want %q", record.NativeHead, localScope.Head.ID)
	}

	requests := service.recordedRequests()
	sawLocalSnapshotPut := false
	for _, request := range requests {
		if strings.Contains(request.Path, "android-pc-ab12") || strings.Contains(request.Path, archiveName) {
			if request.Method != http.MethodGet {
				t.Fatalf("source scope received %s %s", request.Method, request.Path)
			}
		}
		if request.Method == http.MethodDelete {
			t.Fatalf("cross-device restore sent a DELETE to %s", request.Path)
		}
		if request.Method == http.MethodPut &&
			strings.HasPrefix(request.Path, "/v1/opensave/scopes/pc/"+cloudGame+"/snapshots/") {
			sawLocalSnapshotPut = true
			if request.IfMatch != `"`+preHeadID+`"` {
				t.Fatalf("local snapshot If-Match = %q, want %q", request.IfMatch, preHeadID)
			}
		}
	}
	if !sawLocalSnapshotPut {
		t.Fatal("the restored snapshot was not published to the local scope")
	}
}

func TestCrossRestoreApplyAndroidWritesOnlyMappedLocation(t *testing.T) {
	service := newCrossRestoreTestServer(t)
	device := newCrossRestoreDevice(t, "Desktop", "desktop-node", service.url(), "switch")
	configDir := filepath.Join(t.TempDir(), "config")
	if err := os.MkdirAll(configDir, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := device.d.Store.AddGameRoot(cloudGame, "config", configDir); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(configDir, "cfg.dat"), []byte("local config"), 0o666); err != nil {
		t.Fatal(err)
	}
	device.play("base")
	service.clearRequests()
	preHead := service.scope("switch", cloudGame).Head
	if preHead == nil || preHead.ID == "" {
		t.Fatal("local scope has no head")
	}
	preHeadID := preHead.ID

	archiveName := "hollow-knight__main__snap_1800000000000--dev-source.zip"
	data := crossRestoreAndroidData(t)
	service.addArchive("switch", archiveName, data)
	source := crossRestoreSourceSnapshot(archiveName, data, "Pixel 8", "android")
	service.addSnapshot("switch", "android-switch-ab12", source, false)

	reached, release := service.blockUploads()
	defer release()

	result, err := device.d.ApplyCrossDeviceRestore(cloudGame, "android-switch-ab12", source.ID,
		[]snapshot.ForeignAssignment{{ArchiveLocation: "location-0000", LocalRoot: ""}})
	if err != nil {
		t.Fatal(err)
	}
	if result.RestoredSnapshotID == "" {
		t.Fatalf("result = %+v", result)
	}
	if got := string(mustReadCrossRestoreFile(t, filepath.Join(device.save, "slot.sav"))); got != "android save" {
		t.Fatalf("restored save = %q", got)
	}
	if _, err := os.Stat(filepath.Join(device.save, "user1.dat")); !os.IsNotExist(err) {
		t.Fatalf("main save was not replaced: %v", err)
	}
	if got := string(mustReadCrossRestoreFile(t, filepath.Join(configDir, "cfg.dat"))); got != "local config" {
		t.Fatalf("unmapped local location changed: %q", got)
	}
	for _, path := range []string{
		filepath.Join(device.save, "other.dat"),
		filepath.Join(configDir, "other.dat"),
		filepath.Join(device.save, "opensave-game.json"),
		filepath.Join(configDir, "opensave-game.json"),
	} {
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("unmapped archive content %q was restored: %v", path, err)
		}
	}

	snaps, err := device.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(snaps) != 3 || snaps[0].ID != result.RestoredSnapshotID || snaps[1].ID != result.SafetySnapshotID {
		t.Fatalf("snapshots = %+v", snaps)
	}
	safetyFiles := readCrossRestoreZip(t, snaps[1].ZipPath)
	if safetyFiles["user1.dat"] != "base" ||
		safetyFiles[".opensave-locations/config/cfg.dat"] != "local config" {
		t.Fatalf("safety archive = %+v", safetyFiles)
	}

	record, _, err := device.d.Store.GetCloudHead(cloudGame)
	if err != nil {
		t.Fatal(err)
	}
	if record.NativeHead != preHeadID {
		t.Fatalf("NativeHead = %q before upload, want %q", record.NativeHead, preHeadID)
	}
	select {
	case <-reached:
	case <-time.After(5 * time.Second):
		t.Fatal("the restored snapshot was never uploaded")
	}
	release()
	if !device.d.uploads.Wait(5 * time.Second) {
		t.Fatal("restored snapshot upload did not finish")
	}
	localScope := service.scope("switch", cloudGame)
	if localScope.Head == nil || localScope.Head.Parent != preHeadID {
		t.Fatalf("local scope after upload = %+v, want a child of %q", localScope.Head, preHeadID)
	}
	record, _, _ = device.d.Store.GetCloudHead(cloudGame)
	if record.NativeHead != localScope.Head.ID {
		t.Fatalf("NativeHead = %q after upload, want %q", record.NativeHead, localScope.Head.ID)
	}
	sawLocalSnapshotPut := false
	for _, request := range service.recordedRequests() {
		if strings.Contains(request.Path, "android-switch-ab12") || strings.Contains(request.Path, archiveName) {
			if request.Method != http.MethodGet {
				t.Fatalf("source scope received %s %s", request.Method, request.Path)
			}
		}
		if request.Method == http.MethodPut &&
			strings.HasPrefix(request.Path, "/v1/opensave/scopes/switch/"+cloudGame+"/snapshots/") {
			sawLocalSnapshotPut = true
			if request.IfMatch != `"`+preHeadID+`"` {
				t.Fatalf("local snapshot If-Match = %q, want %q", request.IfMatch, preHeadID)
			}
		}
	}
	if !sawLocalSnapshotPut {
		t.Fatal("the restored snapshot was not published to the local scope")
	}
}

func TestCrossRestoreApplyRejectsDigestMismatchBeforeSafety(t *testing.T) {
	service := newCrossRestoreTestServer(t)
	device := newCrossRestoreDevice(t, "Desktop", "desktop-node", service.url(), "pc")
	if err := os.WriteFile(filepath.Join(device.save, "user1.dat"), []byte("keep me"), 0o666); err != nil {
		t.Fatal(err)
	}
	before := crossRestoreTree(t, device.save)
	snapsBefore, err := device.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}

	archiveName := "hollow-knight__main__snap_1800000000000--dev-source.zip"
	data := crossRestorePCData(t)
	service.addArchive("pc", archiveName, data)
	source := crossRestoreSourceSnapshot(archiveName, data, "Steam Deck", "linux")
	source.ContentDigest = strings.Repeat("0", 64)
	service.addSnapshot("pc", "android-pc-ab12", source, false)

	_, err = device.d.ApplyCrossDeviceRestore(cloudGame, "android-pc-ab12", source.ID,
		[]snapshot.ForeignAssignment{{ArchiveLocation: snapshot.ForeignPCRoot, LocalRoot: ""}})
	if err == nil || !strings.Contains(err.Error(), "failed verification") {
		t.Fatalf("error = %v, want a verification failure", err)
	}
	sameCrossRestoreTree(t, before, crossRestoreTree(t, device.save))
	snapsAfter, err := device.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(snapsAfter) != len(snapsBefore) {
		t.Fatalf("a digest mismatch created a safety snapshot: before %d, after %d", len(snapsBefore), len(snapsAfter))
	}
}

func TestCrossRestoreRejectsConflictAndOwnScope(t *testing.T) {
	service := newCrossRestoreTestServer(t)
	device := newCrossRestoreDevice(t, "Desktop", "desktop-node", service.url(), "pc")
	archiveName := "hollow-knight__main__snap_1800000000000--dev-source.zip"
	data := crossRestorePCData(t)
	service.addArchive("pc", archiveName, data)
	source := crossRestoreSourceSnapshot(archiveName, data, "Steam Deck", "linux")
	service.addSnapshot("pc", "android-pc-ab12", source, true)

	if _, err := device.d.PlanCrossDeviceRestore(cloudGame, "android-pc-ab12", source.ID); !errors.Is(err, ErrCrossRestoreInvalid) {
		t.Fatalf("conflict copy error = %v, want ErrCrossRestoreInvalid", err)
	}
	if _, err := device.d.PlanCrossDeviceRestore(cloudGame, cloudGame, source.ID); !errors.Is(err, ErrCrossRestoreInvalid) ||
		!strings.Contains(err.Error(), "own cloud scope") {
		t.Fatalf("own scope error = %v, want ErrCrossRestoreInvalid", err)
	}
}

func TestCrossRestoreApplyRefusesSaveInUse(t *testing.T) {
	service := newCrossRestoreTestServer(t)
	device := newCrossRestoreDevice(t, "Desktop", "desktop-node", service.url(), "pc")
	if err := os.WriteFile(filepath.Join(device.save, "user1.dat"), []byte("keep me"), 0o666); err != nil {
		t.Fatal(err)
	}
	before := crossRestoreTree(t, device.save)
	snapsBefore, err := device.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	archiveName := "hollow-knight__main__snap_1800000000000--dev-source.zip"
	data := crossRestorePCData(t)
	service.addArchive("pc", archiveName, data)
	source := crossRestoreSourceSnapshot(archiveName, data, "Steam Deck", "linux")
	service.addSnapshot("pc", "android-pc-ab12", source, false)

	old := crossRestoreSaveState
	crossRestoreSaveState = func(*Daemon, store.Game) saveState { return saveInUse }
	defer func() { crossRestoreSaveState = old }()

	_, err = device.d.ApplyCrossDeviceRestore(cloudGame, "android-pc-ab12", source.ID,
		[]snapshot.ForeignAssignment{{ArchiveLocation: snapshot.ForeignPCRoot, LocalRoot: ""}})
	if !errors.Is(err, ErrCrossRestoreInUse) {
		t.Fatalf("error = %v, want ErrCrossRestoreInUse", err)
	}
	sameCrossRestoreTree(t, before, crossRestoreTree(t, device.save))
	snapsAfter, err := device.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(snapsAfter) != len(snapsBefore) {
		t.Fatalf("an in-use save created a snapshot: before %d, after %d", len(snapsBefore), len(snapsAfter))
	}
}

func TestCrossRestoreLocalKindAndSuggestions(t *testing.T) {
	kinds := map[string]string{
		"switch":          "switch-title",
		"emulator-ppsspp": "ppsspp",
		"pc":              "gamehub-pc",
		"":                "gamehub-pc",
		"android":         "",
		"some-other-type": "",
	}
	for saveType, want := range kinds {
		if got := crossRestoreLocalKind(saveType); got != want {
			t.Errorf("crossRestoreLocalKind(%q) = %q, want %q", saveType, got, want)
		}
	}

	pcArchive := snapshot.ForeignArchive{
		Format:    snapshot.ForeignFormatPC,
		Locations: []snapshot.ForeignLocation{{ID: snapshot.ForeignPCRoot}},
	}
	oneLocal := []CrossRestoreLocalLocation{{Root: "", Path: "/save", Kind: "gamehub-pc"}}
	if got := crossRestoreSuggestions(pcArchive, oneLocal); len(got) != 1 ||
		got[0].ArchiveLocation != snapshot.ForeignPCRoot || got[0].LocalRoot != "" {
		t.Fatalf("PC one-location suggestions = %+v", got)
	}
	twoLocals := append(oneLocal, CrossRestoreLocalLocation{Root: "config", Path: "/cfg"})
	if got := crossRestoreSuggestions(pcArchive, twoLocals); len(got) != 0 {
		t.Fatalf("PC two-location suggestions = %+v, want none", got)
	}

	androidArchive := snapshot.ForeignArchive{
		Format: snapshot.ForeignFormatAndroid,
		Locations: []snapshot.ForeignLocation{
			{ID: "location-0000", Kind: "switch-title"},
			{ID: "location-0001", Kind: "android-game"},
		},
	}
	androidLocals := []CrossRestoreLocalLocation{
		{Root: "", Path: "/save", Kind: "switch-title"},
		{Root: "config", Path: "/cfg"},
	}
	got := crossRestoreSuggestions(androidArchive, androidLocals)
	if len(got) != 1 || got[0].ArchiveLocation != "location-0000" || got[0].LocalRoot != "" {
		t.Fatalf("Android suggestions = %+v, want the switch-title pair", got)
	}

	duplicateKind := snapshot.ForeignArchive{
		Format: snapshot.ForeignFormatAndroid,
		Locations: []snapshot.ForeignLocation{
			{ID: "location-0000", Kind: "switch-title"},
			{ID: "location-0001", Kind: "switch-title"},
		},
	}
	if got := crossRestoreSuggestions(duplicateKind, androidLocals); len(got) != 0 {
		t.Fatalf("ambiguous kind suggestions = %+v, want none", got)
	}
}
