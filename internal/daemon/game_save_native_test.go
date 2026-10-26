package daemon

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

const nativeGameSaveTestToken = "native-game-save-test-token"

// nativeGameSaveTestServer is a narrow in-memory implementation of the
// published native archive and CAS-scope contract. It lets the desktop client
// regression tests exercise the same request order used against game-save.
type nativeGameSaveTestServer struct {
	mu                 sync.Mutex
	archives           map[string][]byte
	scopes             map[string]*nativeGameSaveTestScope
	races              map[string]cloud.GameSaveScopeSnapshot
	scopeRoutesEnabled bool
	server             *httptest.Server
}

type nativeGameSaveTestScope struct {
	head      string
	snapshots map[string]cloud.GameSaveScopeSnapshot
	conflicts map[string]cloud.GameSaveScopeSnapshot
}

func newNativeGameSaveTestServer(t *testing.T) *nativeGameSaveTestServer {
	t.Helper()
	s := &nativeGameSaveTestServer{
		archives:           map[string][]byte{},
		scopes:             map[string]*nativeGameSaveTestScope{},
		races:              map[string]cloud.GameSaveScopeSnapshot{},
		scopeRoutesEnabled: true,
	}
	s.server = httptest.NewServer(http.HandlerFunc(s.serveHTTP))
	t.Cleanup(s.server.Close)
	return s
}

func (s *nativeGameSaveTestServer) url() string { return s.server.URL }

func nativeGameSaveScopeKey(kind, gameID string) string { return kind + "/" + gameID }

func (s *nativeGameSaveTestServer) scopeLocked(kind, gameID string) *nativeGameSaveTestScope {
	key := nativeGameSaveScopeKey(kind, gameID)
	if state := s.scopes[key]; state != nil {
		return state
	}
	state := &nativeGameSaveTestScope{
		snapshots: map[string]cloud.GameSaveScopeSnapshot{},
		conflicts: map[string]cloud.GameSaveScopeSnapshot{},
	}
	s.scopes[key] = state
	return state
}

func (s *nativeGameSaveTestServer) serveHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer "+nativeGameSaveTestToken {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	if strings.TrimSpace(r.Header.Get("X-OpenSave-Device-ID")) == "" {
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) >= 3 && parts[0] == "v1" && parts[1] == "opensave" && parts[2] == "files" {
		s.handleFiles(w, r, parts)
		return
	}
	if len(parts) >= 3 && parts[0] == "v1" && parts[1] == "opensave" && parts[2] == "scopes" {
		if !s.scopeRoutesEnabled {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		s.handleScopes(w, r, parts)
		return
	}
	w.WriteHeader(http.StatusNotFound)
}

func (s *nativeGameSaveTestServer) handleFiles(w http.ResponseWriter, r *http.Request, parts []string) {
	if len(parts) != 5 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	kind, name := parts[3], parts[4]
	key := kind + "/" + name
	switch r.Method {
	case http.MethodPut:
		body, err := io.ReadAll(r.Body)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
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
	case http.MethodGet:
		s.mu.Lock()
		body, exists := s.archives[key]
		s.mu.Unlock()
		if !exists {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write(body)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (s *nativeGameSaveTestServer) handleScopes(w http.ResponseWriter, r *http.Request, parts []string) {
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
	key := nativeGameSaveScopeKey(kind, gameID)
	if operation == "snapshots" {
		if race, pending := s.races[key]; pending {
			delete(s.races, key)
			state.snapshots[race.ID] = race
			state.head = race.ID
		}
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
	state.conflicts[candidate.ID] = stored
	writeNativeGameSaveJSON(w, http.StatusCreated, stored)
}

func (s *nativeGameSaveTestServer) validateArchiveLocked(kind string, candidate cloud.GameSaveScopeCandidate) error {
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

func (s *nativeGameSaveTestServer) scopeResponseLocked(kind, gameID string) cloud.GameSaveScope {
	state := s.scopeLocked(kind, gameID)
	response := cloud.GameSaveScope{Type: kind, GameID: gameID}
	if state.head != "" {
		head := state.snapshots[state.head]
		response.Head = &head
	}
	for _, item := range state.snapshots {
		response.Snapshots = append(response.Snapshots, item)
	}
	for _, item := range state.conflicts {
		response.Conflicts = append(response.Conflicts, item)
	}
	return response
}

func (s *nativeGameSaveTestServer) scope(kind, gameID string) cloud.GameSaveScope {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.scopeResponseLocked(kind, gameID)
}

// armCASRace advances a scope immediately after a client reads it but before
// that client submits its CAS write, exercising the real 409 conflict path.
func (s *nativeGameSaveTestServer) armCASRace(kind, gameID string, snapshot cloud.GameSaveScopeSnapshot) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.races[nativeGameSaveScopeKey(kind, gameID)] = snapshot
}

func writeNativeGameSaveJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

type nativeCloudDevice struct {
	t      *testing.T
	d      *Daemon
	save   string
	nodeID string
}

func newNativeCloudDevice(t *testing.T, name, nodeID, serviceURL string) *nativeCloudDevice {
	t.Helper()
	d := newTestDaemon(t)
	settings, err := d.Store.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	settings.NodeID = nodeID
	settings.DeviceName = name
	settings.CloudAutoPull = true
	if err := d.Store.UpdateSettings(settings); err != nil {
		t.Fatal(err)
	}
	// NodeID is intentionally immutable through ordinary settings updates. The
	// service-generated value is the identity the native client will actually
	// use, so capture it after the update instead of assuming nodeID was applied.
	storedSettings, err := d.Store.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	config, err := d.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	config.Enabled = true
	config.Provider = cloud.GameSaveProvider
	config.URL = serviceURL
	config.Password = nativeGameSaveTestToken
	if err := d.Store.UpdateCloudConfig(config); err != nil {
		t.Fatal(err)
	}
	save := t.TempDir()
	if err := d.Store.CreateGame(store.Game{
		ID: cloudGame, Name: "Hollow Knight", SavePath: save, ActiveBranch: "main",
		AutoSync: true, MaxSnapshots: 50, SaveType: "switch",
	}); err != nil {
		t.Fatal(err)
	}
	return &nativeCloudDevice{t: t, d: d, save: save, nodeID: storedSettings.NodeID}
}

func (c *nativeCloudDevice) write(content string) {
	c.t.Helper()
	if err := os.WriteFile(filepath.Join(c.save, "user1.dat"), []byte(content), 0o666); err != nil {
		c.t.Fatal(err)
	}
}

func (c *nativeCloudDevice) play(content string) store.Snapshot {
	c.t.Helper()
	c.write(content)
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

func (c *nativeCloudDevice) check() []CloudOffer {
	c.t.Helper()
	c.d.CheckCloud()
	if !c.d.uploads.Wait(5 * time.Second) {
		c.t.Fatal("native game-save check did not finish")
	}
	return c.d.CloudOffers()
}

func (c *nativeCloudDevice) saveIs() string {
	c.t.Helper()
	contents, err := os.ReadFile(filepath.Join(c.save, "user1.dat"))
	if err != nil {
		c.t.Fatal(err)
	}
	return string(contents)
}

func TestNativeGameSaveScopesPullContinuationsAndRetainDivergence(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	deck := newNativeCloudDevice(t, "Steam Deck", "deck-node", service.url())

	base := desktop.play("base")
	baseHead := cloud.GameSaveSnapshotID(base.ID, desktop.nodeID)
	if got := service.scope("switch", cloudGame).Head; got == nil || got.ID != baseHead {
		t.Fatalf("first native scope head = %+v, want %q", got, baseHead)
	}

	if offers := deck.check(); len(offers) != 0 {
		t.Fatalf("empty device was offered a continuation instead of pulling it: %+v", offers)
	}
	if got := deck.saveIs(); got != "base" {
		t.Fatalf("new device save = %q, want base", got)
	}
	deckHead, ok, err := deck.d.Store.GetCloudHead(cloudGame)
	if err != nil || !ok || deckHead.Snapshot != baseHead || deckHead.NativeHead != baseHead {
		t.Fatalf("pulled native head = (%+v, %v, %v), want remote %q confirmed", deckHead, ok, err, baseHead)
	}
	if got := service.scope("switch", cloudGame).Head; got == nil || got.ID != baseHead {
		t.Fatalf("new device republished over scope head: %+v", got)
	}

	continued := desktop.play("desktop continuation")
	continuedHead := cloud.GameSaveSnapshotID(continued.ID, desktop.nodeID)
	if offers := deck.check(); len(offers) != 0 {
		t.Fatalf("continuation was offered instead of auto-pulled: %+v", offers)
	}
	if got := deck.saveIs(); got != "desktop continuation" {
		t.Fatalf("continuation save = %q", got)
	}
	deckHead, ok, err = deck.d.Store.GetCloudHead(cloudGame)
	if err != nil || !ok || deckHead.Snapshot != continuedHead || deckHead.NativeHead != continuedHead {
		t.Fatalf("continued pull head = (%+v, %v, %v), want %q", deckHead, ok, err, continuedHead)
	}

	remote := desktop.play("desktop fork")
	remoteHead := cloud.GameSaveSnapshotID(remote.ID, desktop.nodeID)
	local := deck.play("deck fork")
	localHead := cloud.GameSaveSnapshotID(local.ID, deck.nodeID)
	scope := service.scope("switch", cloudGame)
	if scope.Head == nil || scope.Head.ID != remoteHead {
		t.Fatalf("divergent upload moved cloud head = %+v, want %q", scope.Head, remoteHead)
	}
	if len(scope.Conflicts) != 1 || scope.Conflicts[0].ID != localHead || scope.Conflicts[0].ConflictOf != remoteHead {
		t.Fatalf("scope conflicts = %+v, want retained %q against %q", scope.Conflicts, localHead, remoteHead)
	}
	if offers := deck.check(); len(offers) != 1 || offers[0].SnapshotID != remoteHead || offers[0].ReviewReason != "local-changed" {
		t.Fatalf("divergent device offers = %+v, want review of %q", offers, remoteHead)
	}
	if got := deck.saveIs(); got != "deck fork" {
		t.Fatalf("divergent local save was overwritten with %q", got)
	}
}

func TestNativeGameSaveCASRaceRetainsConflict(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	base := desktop.play("base")
	baseHead := cloud.GameSaveSnapshotID(base.ID, desktop.nodeID)

	raceID := "snap_1800000000000--dev-racer"
	service.armCASRace("switch", cloudGame, cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
			ID:        raceID,
			Archive:   cloudGame + "__main__" + raceID + ".zip",
			Parent:    baseHead,
			CreatedAt: time.Now().UTC(),
		},
		DeviceName: "Racing device",
	})
	local := desktop.play("local write lost the race")
	localHead := cloud.GameSaveSnapshotID(local.ID, desktop.nodeID)
	scope := service.scope("switch", cloudGame)
	if scope.Head == nil || scope.Head.ID != raceID {
		t.Fatalf("CAS race head = %+v, want %q", scope.Head, raceID)
	}
	if len(scope.Conflicts) != 1 || scope.Conflicts[0].ID != localHead || scope.Conflicts[0].ConflictOf != raceID {
		t.Fatalf("CAS race conflicts = %+v, want retained %q", scope.Conflicts, localHead)
	}
}

func TestNativeGameSaveFallsBackToLegacyHeadOnlyWhenScopeRouteIsAbsent(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	service.scopeRoutesEnabled = false
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	local := desktop.play("legacy-compatible save")

	service.mu.Lock()
	defer service.mu.Unlock()
	archiveName := cloudSnapshotName(cloudGame, "main", local.ID)
	if _, ok := service.archives["switch/"+archiveName]; !ok {
		t.Fatalf("legacy archive %q was not uploaded: %+v", archiveName, service.archives)
	}
	var headName string
	for key := range service.archives {
		kind, name, found := strings.Cut(key, "/")
		if found && kind == "switch" {
			if gameID, _, _, isHead := cloud.ParseHeadFileName(name); isHead && gameID == cloudGame {
				headName = name
				break
			}
		}
	}
	if headName == "" {
		t.Fatalf("legacy scope fallback did not publish a head announcement: %+v", service.archives)
	}
}
