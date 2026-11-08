package daemon

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

const gameSaveGroupsTestToken = "game-save-groups-test-token"

// gameSaveGroupsTestServer serves only the read-only routes these tests use
// and fails the test on any write, proving the client never mutates a scope.
type gameSaveGroupsTestServer struct {
	t           *testing.T
	mu          sync.Mutex
	gamesStatus int
	games       []cloud.GameSaveGame
	scopes      map[string]cloud.GameSaveScope
	methods     []string
	deviceIDs   []string
	server      *httptest.Server
}

func newGameSaveGroupsTestServer(t *testing.T) *gameSaveGroupsTestServer {
	t.Helper()
	s := &gameSaveGroupsTestServer{
		t:      t,
		scopes: map[string]cloud.GameSaveScope{},
	}
	s.server = httptest.NewServer(http.HandlerFunc(s.serveHTTP))
	t.Cleanup(s.server.Close)
	return s
}

func (s *gameSaveGroupsTestServer) url() string { return s.server.URL }

func gameSaveGroupsScopeKey(saveType, gameID string) string {
	return saveType + "\x00" + gameID
}

func (s *gameSaveGroupsTestServer) setGames(games []cloud.GameSaveGame) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.games = games
}

func (s *gameSaveGroupsTestServer) setScope(saveType, gameID string, scope cloud.GameSaveScope) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.scopes[gameSaveGroupsScopeKey(saveType, gameID)] = scope
}

func (s *gameSaveGroupsTestServer) requests() (methods, deviceIDs []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.methods...), append([]string(nil), s.deviceIDs...)
}

func (s *gameSaveGroupsTestServer) serveHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer "+gameSaveGroupsTestToken {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	s.mu.Lock()
	s.methods = append(s.methods, r.Method)
	s.deviceIDs = append(s.deviceIDs, r.Header.Get("X-OpenSave-Device-ID"))
	s.mu.Unlock()

	if r.Method != http.MethodGet {
		s.t.Errorf("read-only game group request used %s %s", r.Method, r.URL.Path)
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if r.URL.Path == "/v1/opensave/games" {
		s.mu.Lock()
		status := s.gamesStatus
		games := append([]cloud.GameSaveGame(nil), s.games...)
		s.mu.Unlock()
		if status != 0 && status != http.StatusOK {
			w.WriteHeader(status)
			return
		}
		writeGameSaveGroupsJSON(w, http.StatusOK, map[string]any{"games": games})
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/opensave/scopes/") {
		parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
		if len(parts) != 5 {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		saveType, err := url.PathUnescape(parts[3])
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		gameID, err := url.PathUnescape(parts[4])
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		scope, ok := s.scopes[gameSaveGroupsScopeKey(saveType, gameID)]
		s.mu.Unlock()
		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		writeGameSaveGroupsJSON(w, http.StatusOK, scope)
		return
	}
	w.WriteHeader(http.StatusNotFound)
}

func writeGameSaveGroupsJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func newGameSaveGroupsDaemon(t *testing.T, serviceURL, gameID, name, saveType, appID string) *Daemon {
	t.Helper()
	d := newTestDaemon(t)
	cfg, err := d.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	cfg.Enabled, cfg.Provider, cfg.URL, cfg.Password = true, cloud.GameSaveProvider, serviceURL, gameSaveGroupsTestToken
	if err := d.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}
	if err := d.Store.CreateGame(store.Game{
		ID: gameID, Name: name, SavePath: t.TempDir(), ActiveBranch: "main",
		SaveType: saveType, AppID: appID,
	}); err != nil {
		t.Fatal(err)
	}
	return d
}

func gameSaveGroupsSnapshot(id, archive string, received time.Time) cloud.GameSaveScopeSnapshot {
	return cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{ID: id, Archive: archive, CreatedAt: received},
		DeviceName:             "Pixel 8",
		Platform:               "android",
		ReceivedAt:             received,
	}
}

func TestGameSaveGroupListsOtherDevices(t *testing.T) {
	service := newGameSaveGroupsTestServer(t)
	const localID = "switch-0100000000010000"
	base := time.Date(2026, 11, 8, 10, 0, 0, 0, time.UTC)
	localHead := gameSaveGroupsSnapshot("local-head", "local.zip", base.Add(4*time.Hour))
	localHead.DeviceName, localHead.Platform = "Desktop", "opensave-windows"
	newHead := gameSaveGroupsSnapshot("new-head", "new.zip", base.Add(2*time.Hour))
	newHead.DeviceName, newHead.Platform = "Pixel 8", "android"
	oldHead := gameSaveGroupsSnapshot("old-head", "old.zip", base.Add(time.Hour))
	oldHead.DeviceName, oldHead.Platform = "Steam Deck", "opensave-linux"
	service.setGames([]cloud.GameSaveGame{{
		Key: "switch/title:0100000000010000", Type: "switch", Name: "Super Mario Odyssey",
		LastReceivedAt: base.Add(4 * time.Hour),
		Scopes: []cloud.GameSaveGameMember{
			{Type: "switch", GameID: localID, Link: "auto", Head: &localHead, SnapshotCount: 9, LastReceivedAt: base.Add(4 * time.Hour)},
			{Type: "switch", GameID: "android-old", Link: "auto", Name: "Old phone", Head: &oldHead, SnapshotCount: 1},
			{Type: "switch", GameID: "desktop-null", Link: "none", Name: "Desktop", SnapshotCount: 4, ConflictCount: 1, LastReceivedAt: base.Add(2 * time.Hour)},
			{Type: "switch", GameID: "android-new", Link: "auto", Name: "Pixel 8", Head: &newHead, SnapshotCount: 3, LastReceivedAt: base.Add(3 * time.Hour)},
		},
	}})
	d := newGameSaveGroupsDaemon(t, service.url(), localID, "Super Mario Odyssey", "switch", "")

	group, err := d.GameSaveGroup(localID)
	if err != nil {
		t.Fatal(err)
	}
	if !group.Configured || !group.Supported || group.State != gameSaveGroupOK || group.Detail != "" {
		t.Fatalf("group status = %+v", group)
	}
	if group.GameKey != "switch/title:0100000000010000" || group.GameName != "Super Mario Odyssey" || group.SaveType != "switch" {
		t.Fatalf("group identity = %+v", group)
	}
	if len(group.Members) != 3 {
		t.Fatalf("members = %+v, want three other devices", group.Members)
	}
	wantOrder := []string{"android-new", "desktop-null", "android-old"}
	for i, want := range wantOrder {
		if group.Members[i].GameID != want {
			t.Fatalf("member order = %+v, want %v", group.Members, wantOrder)
		}
	}
	newMember := group.Members[0]
	if newMember.Type != "switch" || newMember.Name != "Pixel 8" || newMember.Link != "auto" ||
		newMember.DeviceName != "Pixel 8" || newMember.Platform != "android" ||
		newMember.HeadID != "new-head" || newMember.HeadArchive != "new.zip" ||
		newMember.SnapshotCount != 3 || newMember.ConflictCount != 0 ||
		!newMember.LastVersionAt.Equal(base.Add(3*time.Hour)) {
		t.Fatalf("new member = %+v", newMember)
	}
	nullMember := group.Members[1]
	if nullMember.DeviceName != "" || nullMember.Platform != "" || nullMember.HeadID != "" || nullMember.HeadArchive != "" {
		t.Fatalf("headless member leaked head fields: %+v", nullMember)
	}
	if nullMember.SnapshotCount != 4 || nullMember.ConflictCount != 1 ||
		!nullMember.LastVersionAt.Equal(base.Add(2*time.Hour)) {
		t.Fatalf("headless member = %+v", nullMember)
	}
	oldMember := group.Members[2]
	if oldMember.DeviceName != "Steam Deck" || oldMember.Platform != "opensave-linux" ||
		!oldMember.LastVersionAt.Equal(base.Add(time.Hour)) {
		t.Fatalf("member without lastReceivedAt = %+v", oldMember)
	}
	methods, deviceIDs := service.requests()
	if len(methods) != 1 || methods[0] != http.MethodGet {
		t.Fatalf("requests = %v, want one GET", methods)
	}
	if deviceIDs[0] != "" {
		t.Fatalf("read-only group request sent device id %q", deviceIDs[0])
	}
}

func TestGameSaveGroupFindsCanonicalKeyBeforeAnyUpload(t *testing.T) {
	base := time.Date(2026, 11, 8, 10, 0, 0, 0, time.UTC)
	remoteHead := gameSaveGroupsSnapshot("remote-head", "remote.zip", base)
	remoteMember := cloud.GameSaveGameMember{
		Type: "switch", GameID: "android-switch-ab12", Link: "auto", Name: "Pixel 8",
		Head: &remoteHead, SnapshotCount: 1, LastReceivedAt: base,
	}

	t.Run("switch title id", func(t *testing.T) {
		service := newGameSaveGroupsTestServer(t)
		service.setGames([]cloud.GameSaveGame{
			// A same-key group of another type must not be selected.
			{Key: "switch/title:0100000000010000", Type: "pc", Name: "wrong type"},
			{Key: "switch/title:0100000000010000", Type: "switch", Name: "Super Mario Odyssey",
				Scopes: []cloud.GameSaveGameMember{remoteMember}},
		})
		const gameID = "switch-0100000000010000"
		d := newGameSaveGroupsDaemon(t, service.url(), gameID, "Super Mario Odyssey", "switch", "")
		group, err := d.GameSaveGroup(gameID)
		if err != nil {
			t.Fatal(err)
		}
		if group.State != gameSaveGroupOK || group.GameKey != "switch/title:0100000000010000" ||
			group.GameName != "Super Mario Odyssey" || len(group.Members) != 1 ||
			group.Members[0].GameID != "android-switch-ab12" {
			t.Fatalf("group = %+v", group)
		}
	})

	t.Run("steam app id", func(t *testing.T) {
		service := newGameSaveGroupsTestServer(t)
		pcHead := gameSaveGroupsSnapshot("pc-head", "pc.zip", base)
		pcHead.DeviceName, pcHead.Platform = "Desktop", "opensave-windows"
		service.setGames([]cloud.GameSaveGame{
			// A same-key group of another type must not be selected.
			{Key: "pc/steam:1245620", Type: "switch", Name: "wrong type"},
			{Key: "pc/steam:1245620", Type: "pc", Name: "ELDEN RING",
				Scopes: []cloud.GameSaveGameMember{{
					Type: "pc", GameID: "desktop-elden", Link: "auto", Name: "Desktop",
					Head: &pcHead, SnapshotCount: 2, LastReceivedAt: base,
				}}},
		})
		const gameID = "elden-ring"
		d := newGameSaveGroupsDaemon(t, service.url(), gameID, "ELDEN RING", "pc", "1245620")
		group, err := d.GameSaveGroup(gameID)
		if err != nil {
			t.Fatal(err)
		}
		if group.State != gameSaveGroupOK || group.GameKey != "pc/steam:1245620" || len(group.Members) != 1 ||
			group.Members[0].GameID != "desktop-elden" || group.Members[0].DeviceName != "Desktop" {
			t.Fatalf("group = %+v", group)
		}
	})

	t.Run("only another type has the key", func(t *testing.T) {
		service := newGameSaveGroupsTestServer(t)
		service.setGames([]cloud.GameSaveGame{{
			Key: "switch/title:0100000000010000", Type: "pc", Name: "wrong type",
			Scopes: []cloud.GameSaveGameMember{remoteMember},
		}})
		const gameID = "switch-0100000000010000"
		d := newGameSaveGroupsDaemon(t, service.url(), gameID, "Super Mario Odyssey", "switch", "")
		group, err := d.GameSaveGroup(gameID)
		if err != nil {
			t.Fatal(err)
		}
		if group.State != gameSaveGroupNotGrouped || !group.Supported || len(group.Members) != 0 {
			t.Fatalf("group = %+v, want not_grouped without members", group)
		}
		raw, err := json.Marshal(group)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(raw), `"members":[]`) {
			t.Fatalf("members serialized as null: %s", raw)
		}
	})
}

func TestGameSaveGroupReportsUnavailableStates(t *testing.T) {
	const gameID = "switch-0100000000010000"

	t.Run("legacy", func(t *testing.T) {
		service := newGameSaveGroupsTestServer(t)
		service.gamesStatus = http.StatusNotFound
		d := newGameSaveGroupsDaemon(t, service.url(), gameID, "Super Mario Odyssey", "switch", "")
		group, err := d.GameSaveGroup(gameID)
		if err != nil {
			t.Fatal(err)
		}
		if !group.Configured || group.Supported || group.State != gameSaveStatusLegacy ||
			len(group.Members) != 0 {
			t.Fatalf("group = %+v", group)
		}
		raw, err := json.Marshal(group)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(raw), `"members":[]`) {
			t.Fatalf("legacy members serialized as null: %s", raw)
		}
	})

	t.Run("disabled", func(t *testing.T) {
		service := newGameSaveGroupsTestServer(t)
		d := newGameSaveGroupsDaemon(t, service.url(), gameID, "Super Mario Odyssey", "switch", "")
		cfg, err := d.Store.GetCloudConfig()
		if err != nil {
			t.Fatal(err)
		}
		cfg.Provider = "local"
		cfg.URL = t.TempDir()
		if err := d.Store.UpdateCloudConfig(cfg); err != nil {
			t.Fatal(err)
		}
		group, err := d.GameSaveGroup(gameID)
		if err != nil {
			t.Fatal(err)
		}
		if group.Configured || group.Supported || group.State != gameSaveStatusDisabled || len(group.Members) != 0 {
			t.Fatalf("group = %+v", group)
		}
		if methods, _ := service.requests(); len(methods) != 0 {
			t.Fatalf("disabled group still called the service: %v", methods)
		}
	})

	t.Run("offline", func(t *testing.T) {
		service := newGameSaveGroupsTestServer(t)
		serviceURL := service.url()
		service.server.Close()
		d := newGameSaveGroupsDaemon(t, serviceURL, gameID, "Super Mario Odyssey", "switch", "")
		group, err := d.GameSaveGroup(gameID)
		if err != nil {
			t.Fatal(err)
		}
		if !group.Configured || group.Supported || group.State != gameSaveStatusOffline || len(group.Members) != 0 {
			t.Fatalf("group = %+v", group)
		}
	})

	t.Run("server error", func(t *testing.T) {
		service := newGameSaveGroupsTestServer(t)
		service.gamesStatus = http.StatusInternalServerError
		d := newGameSaveGroupsDaemon(t, service.url(), gameID, "Super Mario Odyssey", "switch", "")
		group, err := d.GameSaveGroup(gameID)
		if err != nil {
			t.Fatal(err)
		}
		if !group.Configured || group.Supported || group.State != gameSaveStatusError ||
			group.Detail == "" || len(group.Members) != 0 {
			t.Fatalf("group = %+v", group)
		}
	})
}

func TestGameSaveMemberVersionsListsHeadAndHistory(t *testing.T) {
	service := newGameSaveGroupsTestServer(t)
	const (
		gameID = "switch-0100000000010000"
		member = "android-switch-ab12"
	)
	base := time.Date(2026, 11, 8, 10, 0, 0, 0, time.UTC)
	head := gameSaveGroupsSnapshot("head", "head.zip", base.Add(4*time.Hour))
	old := gameSaveGroupsSnapshot("old", "old.zip", base.Add(2*time.Hour))
	newer := gameSaveGroupsSnapshot("newer", "newer.zip", base.Add(5*time.Hour))
	newer.DeviceName, newer.Platform = "Pixel 8", "android"
	duplicate := old
	duplicate.ReceivedAt = base.Add(time.Hour)
	conflict := gameSaveGroupsSnapshot("conflict", "conflict.zip", base.Add(3*time.Hour))
	conflict.Conflict = true
	conflict.ConflictOf = "head"
	service.setScope("switch", member, cloud.GameSaveScope{
		Type: "switch", GameID: member, Head: &head,
		Snapshots: []cloud.GameSaveScopeSnapshot{head, old, conflict, newer, duplicate},
		Conflicts: []cloud.GameSaveScopeSnapshot{conflict},
	})
	d := newGameSaveGroupsDaemon(t, service.url(), gameID, "Super Mario Odyssey", "switch", "")

	versions, err := d.GameSaveMemberVersions(gameID, member)
	if err != nil {
		t.Fatal(err)
	}
	if len(versions) != 3 {
		t.Fatalf("versions = %+v, want head and two ordinary versions", versions)
	}
	wantIDs := []string{"newer", "head", "old"}
	wantHeads := []bool{false, true, false}
	for i := range wantIDs {
		if versions[i].ID != wantIDs[i] || versions[i].IsHead != wantHeads[i] {
			t.Fatalf("versions[%d] = %+v, want id %q isHead %v", i, versions[i], wantIDs[i], wantHeads[i])
		}
	}
	if versions[1].Archive != "head.zip" || versions[1].DeviceName != "Pixel 8" ||
		versions[1].Platform != "android" || !versions[1].CreatedAt.Equal(base.Add(4*time.Hour)) ||
		!versions[1].ReceivedAt.Equal(base.Add(4*time.Hour)) {
		t.Fatalf("head version = %+v", versions[1])
	}
	if versions[2].ReceivedAt.Equal(base.Add(time.Hour)) {
		t.Fatalf("duplicate snapshot was not deduplicated: %+v", versions[2])
	}
	methods, deviceIDs := service.requests()
	if len(methods) != 1 || methods[0] != http.MethodGet {
		t.Fatalf("requests = %v, want one GET", methods)
	}
	if deviceIDs[0] != "" {
		t.Fatalf("read-only version request sent device id %q", deviceIDs[0])
	}
}

func TestGameSaveMemberVersionsRejectsInvalidAndSameScope(t *testing.T) {
	service := newGameSaveGroupsTestServer(t)
	const gameID = "switch-0100000000010000"
	d := newGameSaveGroupsDaemon(t, service.url(), gameID, "Super Mario Odyssey", "switch", "")

	for _, member := range []string{gameID, ""} {
		if _, err := d.GameSaveMemberVersions(gameID, member); !errors.Is(err, ErrCrossDeviceSameScope) {
			t.Errorf("member %q error = %v, want ErrCrossDeviceSameScope", member, err)
		}
	}
	for _, member := range []string{"bad/id", "bad id", "bad!", strings.Repeat("a", 129)} {
		if _, err := d.GameSaveMemberVersions(gameID, member); !errors.Is(err, ErrInvalidGameSaveMemberID) {
			t.Errorf("member %q error = %v, want ErrInvalidGameSaveMemberID", member, err)
		}
	}
	if methods, _ := service.requests(); len(methods) != 0 {
		t.Fatalf("invalid member ids still reached the service: %v", methods)
	}
}

func TestGameSaveMemberVersionsPropagatesScopeUnsupported(t *testing.T) {
	service := newGameSaveGroupsTestServer(t)
	const gameID = "switch-0100000000010000"
	d := newGameSaveGroupsDaemon(t, service.url(), gameID, "Super Mario Odyssey", "switch", "")
	if _, err := d.GameSaveMemberVersions(gameID, "android-switch-ab12"); !errors.Is(err, cloud.ErrGameSaveScopesUnsupported) {
		t.Fatalf("missing scope error = %v, want ErrGameSaveScopesUnsupported", err)
	}
	if methods, _ := service.requests(); len(methods) != 1 || methods[0] != http.MethodGet {
		t.Fatalf("requests = %v, want one GET", methods)
	}
}

func TestGameSaveGroupsMissingGameIsNotFound(t *testing.T) {
	service := newGameSaveGroupsTestServer(t)
	d := newGameSaveGroupsDaemon(t, service.url(), "switch-0100000000010000", "Super Mario Odyssey", "switch", "")
	if _, err := d.GameSaveGroup("missing"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("group error = %v, want ErrNotFound", err)
	}
	if _, err := d.GameSaveMemberVersions("missing", "android-switch-ab12"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("versions error = %v, want ErrNotFound", err)
	}
}
