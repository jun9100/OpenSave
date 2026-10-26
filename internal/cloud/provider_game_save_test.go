package cloud

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/store"
	"github.com/opensave/opensave/internal/version"
)

func TestNormalizeGameSaveURL(t *testing.T) {
	for _, tc := range []struct {
		in, want string
		bad      bool
	}{
		{"https://nas.example:8788", "https://nas.example:8788", false},
		{"https://nas.example:8788/", "https://nas.example:8788", false},
		{"http://nas.example/opensave/", "http://nas.example", false},
		{"https://nas.example/v1/opensave/", "https://nas.example", false},
		{"https://nas.example/game-save/v1/opensave", "https://nas.example/game-save", false},
		{"https://nas.example/eden", "https://nas.example/eden", false},
		{"", "", false},
		{"nas.example", "", true},
		{"https://u:p@nas.example", "", true},
	} {
		got, err := NormalizeGameSaveURL(tc.in)
		if tc.bad && err == nil {
			t.Errorf("NormalizeGameSaveURL(%q) accepted invalid URL", tc.in)
		}
		if !tc.bad && (err != nil || got != tc.want) {
			t.Errorf("NormalizeGameSaveURL(%q) = %q, %v; want %q", tc.in, got, err, tc.want)
		}
	}
}

func TestGameSaveProviderUsesNativeTypedAPI(t *testing.T) {
	const token = "sync-key"
	var mu sync.Mutex
	files := map[string][]byte{}
	var requestHeaders []http.Header
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+token {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		mu.Lock()
		requestHeaders = append(requestHeaders, r.Header.Clone())
		mu.Unlock()
		if r.URL.Path == "/v1/opensave/files" && r.Method == http.MethodGet {
			mu.Lock()
			defer mu.Unlock()
			items := make([]map[string]any, 0, len(files))
			for key, body := range files {
				parts := strings.SplitN(key, "/", 2)
				items = append(items, map[string]any{"type": parts[0], "name": parts[1], "sizeBytes": len(body), "createdTime": time.Now().UTC()})
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"files": items})
			return
		}
		parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/opensave/files/"), "/")
		if len(parts) != 2 || r.URL.Path == "/v1/opensave/files/" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		key := parts[0] + "/" + parts[1]
		switch r.Method {
		case http.MethodPut:
			body, _ := io.ReadAll(r.Body)
			mu.Lock()
			files[key] = body
			mu.Unlock()
			w.WriteHeader(http.StatusCreated)
		case http.MethodGet:
			mu.Lock()
			body, ok := files[key]
			mu.Unlock()
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			_, _ = w.Write(body)
		case http.MethodDelete:
			mu.Lock()
			delete(files, key)
			mu.Unlock()
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusMethodNotAllowed)
		}
	}))
	defer server.Close()

	svc, data := newTestService(t)
	settings, err := data.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	settings.DeviceName = "Test Windows"
	if err := data.UpdateSettings(settings); err != nil {
		t.Fatal(err)
	}
	var logs []string
	svc.Log = func(_ string, message string) { logs = append(logs, message) }
	if err := data.CreateGame(store.Game{ID: "hollow-knight", Name: "Hollow Knight", SavePath: t.TempDir(), SaveType: "switch"}); err != nil {
		t.Fatal(err)
	}
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, token
	})
	const name = "hollow-knight__main__snap_1.zip"
	if err := svc.Upload(writeTempZip(t, "snapshot bytes"), name); err != nil {
		t.Fatal(err)
	}
	if joined := strings.Join(logs, "\n"); !strings.Contains(joined, "via game-save") || !strings.Contains(joined, "to game-save") || strings.Contains(joined, "eden_syncd") {
		t.Fatalf("game-save activity log = %q", joined)
	}
	headName := HeadFileName("hollow-knight", "switch-device", time.Now())
	if err := svc.Upload(writeTempZip(t, "head bytes"), headName); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	_, snapshotInSwitch := files["switch/"+name]
	_, headInSwitch := files["switch/"+headName]
	_, headInPC := files["pc/"+headName]
	mu.Unlock()
	if !snapshotInSwitch || !headInSwitch || headInPC {
		t.Fatalf("typed uploads = %+v, want snapshot and head only in switch", files)
	}
	if err := data.AddGameAlias("hollow-knight-other", "hollow-knight"); err != nil {
		t.Fatal(err)
	}
	aliasName := "hollow-knight-other__main__snap_2.zip"
	if err := svc.Upload(writeTempZip(t, "linked snapshot bytes"), aliasName); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	_, aliasInSwitch := files["switch/"+aliasName]
	_, aliasInPC := files["pc/"+aliasName]
	mu.Unlock()
	if !aliasInSwitch || aliasInPC {
		t.Fatalf("linked typed upload = %+v, want alias only in switch", files)
	}
	items, err := svc.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 3 {
		t.Fatalf("List = %+v", items)
	}
	var snapshotItem CloudFile
	for _, item := range items {
		if item.Name == name {
			snapshotItem = item
		}
		if item.Name == headName && item.ID != "switch" {
			t.Fatalf("head list entry = %+v, want switch namespace", item)
		}
	}
	if snapshotItem.Name != name || snapshotItem.ID != "switch" {
		t.Fatalf("List = %+v", items)
	}
	destination := path.Join(t.TempDir(), "download.zip")
	if err := svc.Download(name, destination); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(destination); string(got) != "snapshot bytes" {
		t.Fatalf("download = %q", got)
	}
	if err := svc.Delete(snapshotItem); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(requestHeaders) == 0 {
		t.Fatal("native requests did not reach the test server")
	}
	for _, headers := range requestHeaders {
		for _, family := range []string{"X-OpenSave", "X-Eden"} {
			if got := headers.Get(family + "-Device-ID"); got != settings.NodeID {
				t.Fatalf("%s device ID = %q, want %q", family, got, settings.NodeID)
			}
			if got := headers.Get(family + "-Device-Name"); got != settings.DeviceName {
				t.Fatalf("%s device name = %q, want %q", family, got, settings.DeviceName)
			}
			if got, want := headers.Get(family+"-Platform"), "opensave-"+runtime.GOOS; got != want {
				t.Fatalf("%s platform = %q, want %q", family, got, want)
			}
			if got := headers.Get(family + "-Version"); got != version.Version {
				t.Fatalf("%s version = %q, want %q", family, got, version.Version)
			}
		}
	}
}

func TestScopedNameKeepsNativeSaveTypesSeparate(t *testing.T) {
	const name = "hollow-knight__main__snap_1.zip"
	if pc, switchSave := ScopedName(GameSaveProvider, "pc", name), ScopedName(GameSaveProvider, "switch", name); pc == switchSave {
		t.Fatalf("typed cloud keys collide: %q", pc)
	}
	if got := ScopedName("webdav", "switch", name); got != name {
		t.Fatalf("flat provider key = %q, want %q", got, name)
	}
}

func TestGameSaveScopeUsesCASContract(t *testing.T) {
	const token = "sync-key"
	var headers []http.Header
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+token {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		headers = append(headers, r.Header.Clone())
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/opensave/scopes/switch/hollow-knight":
			_ = json.NewEncoder(w).Encode(GameSaveScope{
				Type:   "switch",
				GameID: "hollow-knight",
				Head:   &GameSaveScopeSnapshot{GameSaveScopeCandidate: GameSaveScopeCandidate{ID: "snap_1--dev-deck"}},
			})
		case r.Method == http.MethodPut && r.URL.Path == "/v1/opensave/scopes/switch/hollow-knight/snapshots/snap_2--dev-desktop":
			if got := r.Header.Get("If-Match"); got != `"snap_1--dev-deck"` {
				w.WriteHeader(http.StatusPreconditionFailed)
				_, _ = w.Write([]byte("unexpected If-Match: " + got))
				return
			}
			var candidate GameSaveScopeCandidate
			if err := json.NewDecoder(r.Body).Decode(&candidate); err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			if candidate.ID != "snap_2--dev-desktop" || candidate.Parent != "snap_1--dev-deck" || candidate.Archive != "hollow-knight__main__snap_2--dev-desktop.zip" {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			w.Header().Set("ETag", `"snap_2--dev-desktop"`)
			_ = json.NewEncoder(w).Encode(GameSaveScopeSnapshot{GameSaveScopeCandidate: candidate})
		case r.Method == http.MethodPut && r.URL.Path == "/v1/opensave/scopes/switch/hollow-knight/conflicts/snap_3--dev-desktop":
			if r.Header.Get("If-Match") != "" {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			var candidate GameSaveScopeCandidate
			if err := json.NewDecoder(r.Body).Decode(&candidate); err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			_ = json.NewEncoder(w).Encode(GameSaveScopeSnapshot{
				GameSaveScopeCandidate: candidate,
				Conflict:               true,
				ConflictOf:             "snap_2--dev-other",
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	svc, data := newTestService(t)
	settings, err := data.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	settings.DeviceName = "Test Desktop"
	if err := data.UpdateSettings(settings); err != nil {
		t.Fatal(err)
	}
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, token
	})

	scope, err := svc.GetGameSaveScope("switch", "hollow-knight")
	if err != nil {
		t.Fatal(err)
	}
	if scope.Head == nil || scope.Head.ID != "snap_1--dev-deck" {
		t.Fatalf("scope = %+v", scope)
	}
	candidate := GameSaveScopeCandidate{
		ID:            "snap_2--dev-desktop",
		Archive:       "hollow-knight__main__snap_2--dev-desktop.zip",
		Parent:        scope.Head.ID,
		ContentDigest: strings.Repeat("a", 64),
		CreatedAt:     time.Date(2026, 10, 26, 0, 0, 0, 0, time.UTC),
	}
	stored, err := svc.PutGameSaveScopeSnapshot("switch", "hollow-knight", candidate, scope.Head.ID)
	if err != nil {
		t.Fatal(err)
	}
	if stored.ID != candidate.ID || stored.Archive != candidate.Archive {
		t.Fatalf("stored snapshot = %+v", stored)
	}
	conflict := candidate
	conflict.ID = "snap_3--dev-desktop"
	conflict.Archive = "hollow-knight__main__snap_3--dev-desktop.zip"
	conflict.Parent = "snap_1--dev-deck"
	retained, err := svc.PutGameSaveScopeConflict("switch", "hollow-knight", conflict)
	if err != nil {
		t.Fatal(err)
	}
	if !retained.Conflict || retained.ConflictOf != "snap_2--dev-other" {
		t.Fatalf("retained conflict = %+v", retained)
	}
	if len(headers) != 3 {
		t.Fatalf("scope request count = %d, want 3", len(headers))
	}
	for _, requestHeaders := range headers {
		for _, family := range []string{"X-OpenSave", "X-Eden"} {
			if got := requestHeaders.Get(family + "-Device-ID"); got != settings.NodeID {
				t.Fatalf("%s device ID = %q, want %q", family, got, settings.NodeID)
			}
			if got := requestHeaders.Get(family + "-Device-Name"); got != settings.DeviceName {
				t.Fatalf("%s device name = %q, want %q", family, got, settings.DeviceName)
			}
		}
	}
}

func TestGameSaveScopeOnlyFallsBackForRouteLevelNotFound(t *testing.T) {
	server := httptest.NewServer(http.NotFoundHandler())
	defer server.Close()
	svc, data := newTestService(t)
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, "sync-key"
	})
	_, err := svc.GetGameSaveScope("pc", "hollow-knight")
	if !errors.Is(err, ErrGameSaveScopesUnsupported) {
		t.Fatalf("scope 404 error = %v, want ErrGameSaveScopesUnsupported", err)
	}

	// A scope route observed successfully is a protocol capability. A later
	// 404 during its write must stay an ordinary transfer failure, not switch
	// this upload back to the timestamped legacy head protocol.
	writeServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/opensave/scopes/pc/hollow-knight":
			_ = json.NewEncoder(w).Encode(GameSaveScope{Type: "pc", GameID: "hollow-knight"})
		case r.Method == http.MethodPut:
			w.WriteHeader(http.StatusNotFound)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer writeServer.Close()
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, writeServer.URL, "sync-key"
	})
	if _, err := svc.GetGameSaveScope("pc", "hollow-knight"); err != nil {
		t.Fatalf("supported scope GET failed: %v", err)
	}
	_, err = svc.PutGameSaveScopeSnapshot("pc", "hollow-knight", GameSaveScopeCandidate{
		ID:            "snap_2--dev-desktop",
		Archive:       "hollow-knight__main__snap_2--dev-desktop.zip",
		ContentDigest: strings.Repeat("a", 64),
		CreatedAt:     time.Now().UTC(),
	}, "")
	if err == nil || errors.Is(err, ErrGameSaveScopesUnsupported) {
		t.Fatalf("scope write 404 error = %v, want a non-fallback transfer failure", err)
	}
}

func TestGameSaveArchiveNamesAreDeviceUnique(t *testing.T) {
	first := GameSaveArchiveName("hollow-knight", "main", "snap_123", "desktop-uuid")
	second := GameSaveArchiveName("hollow-knight", "main", "snap_123", "odin2-uuid")
	if first == second || first == "" || second == "" {
		t.Fatalf("native archive names collide: %q and %q", first, second)
	}
	gameID, branch, remoteID, ok := ParseGameSaveArchiveName(first)
	if !ok || gameID != "hollow-knight" || branch != "main" || remoteID != "snap_123--dev-desktopuuid" {
		t.Fatalf("ParseGameSaveArchiveName(%q) = (%q, %q, %q, %v)", first, gameID, branch, remoteID, ok)
	}
}
