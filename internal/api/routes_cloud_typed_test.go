package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/delta"
	"github.com/opensave/opensave/internal/store"
	"github.com/opensave/opensave/internal/watcher"
)

func TestNativeCloudRoutesKeepSameNamedSaveTypesSeparate(t *testing.T) {
	ts := startTestServer(t)
	const name = "same-game__main__snap_1.zip"

	var deletedPath string
	service := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/opensave/files":
			_ = json.NewEncoder(w).Encode(map[string]any{"files": []map[string]any{
				{"type": "pc", "name": name, "sizeBytes": 10, "createdTime": time.Now().UTC()},
				{"type": "switch", "name": name, "sizeBytes": 20, "createdTime": time.Now().UTC()},
			}})
		case r.Method == http.MethodDelete:
			deletedPath = r.URL.Path
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer service.Close()

	if err := ts.daemon.Store.CreateGame(store.Game{
		ID: "same-game", Name: "Same Game PC", SavePath: ts.saveDir, SaveType: "pc",
	}); err != nil {
		t.Fatal(err)
	}
	cfg, err := ts.daemon.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	cfg.Enabled, cfg.Provider, cfg.URL, cfg.Password = true, cloud.GameSaveProvider, service.URL, "test-key"
	if err := ts.daemon.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}

	resp, err := http.Get(ts.base + "/api/cloud/browse")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var browse []struct {
		GameID   string `json:"gameId"`
		SaveType string `json:"saveType"`
		Count    int    `json:"count"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&browse); err != nil {
		t.Fatal(err)
	}
	if len(browse) != 2 {
		t.Fatalf("browse = %+v, want distinct PC and Switch groups", browse)
	}
	types := map[string]int{}
	for _, group := range browse {
		if group.GameID != "same-game" {
			t.Fatalf("browse group = %+v, want same-game", group)
		}
		types[group.SaveType] = group.Count
	}
	if types["pc"] != 1 || types["switch"] != 1 {
		t.Fatalf("browse types = %+v, want one PC and one Switch snapshot", types)
	}

	list, err := http.Get(ts.base + "/api/cloud/snapshots/same-game")
	if err != nil {
		t.Fatal(err)
	}
	defer list.Body.Close()
	var snapshots []struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	if err := json.NewDecoder(list.Body).Decode(&snapshots); err != nil {
		t.Fatal(err)
	}
	if len(snapshots) != 1 || snapshots[0].ID != "pc" || snapshots[0].Name != name {
		t.Fatalf("PC listing = %+v, want only the PC namespace", snapshots)
	}

	if response, _ := ts.do(t, http.MethodPost, "/api/cloud/restore/same-game", map[string]string{
		"fileName": name, "id": "switch",
	}); response.StatusCode != http.StatusBadRequest {
		t.Errorf("restoring a Switch item into PC returned %d, want 400", response.StatusCode)
	}
	if response, _ := ts.do(t, http.MethodPost, "/api/cloud/delete/same-game", map[string]string{
		"fileName": name, "id": "switch",
	}); response.StatusCode != http.StatusBadRequest {
		t.Errorf("deleting a Switch item through PC returned %d, want 400", response.StatusCode)
	}
	if deletedPath != "" {
		t.Errorf("cross-type delete reached native service at %q", deletedPath)
	}

	if response, _ := ts.do(t, http.MethodPost, "/api/cloud/delete/same-game", map[string]string{
		"fileName": name, "id": "pc",
	}); response.StatusCode != http.StatusOK {
		t.Errorf("deleting a PC item returned %d, want 200", response.StatusCode)
	}
	if !strings.Contains(deletedPath, "/pc/") {
		t.Errorf("native delete path = %q, want PC namespace", deletedPath)
	}
}

func TestNativeCloudStatusShowsConflictAndBlocksOrdinaryRestore(t *testing.T) {
	ts := startTestServer(t)
	const (
		gameID      = "same-game"
		archiveName = "same-game__main__snap_1--dev-desktop.zip"
		headID      = "snap_1--dev-desktop"
		conflictID  = "snap_2--dev-handheld"
	)

	var scopeReads, writes int
	service := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.Method == http.MethodGet && r.URL.Path == "/v1/opensave/scopes/pc/"+gameID {
			scopeReads++
			if got := r.Header.Get("X-OpenSave-Device-ID"); got != "" {
				t.Errorf("read-only status sent device ID %q", got)
			}
			now := time.Now().UTC()
			head := cloud.GameSaveScopeSnapshot{
				GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
					ID: headID, Archive: archiveName, CreatedAt: now.Add(-time.Minute),
				},
				Device: "desktop", DeviceName: "Desktop", Platform: "opensave-windows", ReceivedAt: now,
			}
			conflict := cloud.GameSaveScopeSnapshot{
				GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
					ID: conflictID, Archive: "same-game__main__snap_2--dev-handheld.zip", Parent: headID, CreatedAt: now,
				},
				Device: "handheld", DeviceName: "Handheld", Platform: "opensave-android", ReceivedAt: now,
				Conflict: true, ConflictOf: headID,
			}
			_ = json.NewEncoder(w).Encode(cloud.GameSaveScope{
				Type: "pc", GameID: gameID, Head: &head,
				Snapshots: []cloud.GameSaveScopeSnapshot{head, conflict},
				Conflicts: []cloud.GameSaveScopeSnapshot{conflict},
			})
			return
		}
		writes++
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer service.Close()

	if err := ts.daemon.Store.CreateGame(store.Game{
		ID: gameID, Name: "Same Game", SavePath: ts.saveDir, SaveType: "pc",
	}); err != nil {
		t.Fatal(err)
	}
	cfg, err := ts.daemon.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	cfg.Enabled, cfg.Provider, cfg.URL, cfg.Password = true, cloud.GameSaveProvider, service.URL, "test-key"
	if err := ts.daemon.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}

	response, body := ts.do(t, http.MethodGet, "/api/cloud/status/"+gameID, nil)
	if response.StatusCode != http.StatusOK || string(body["state"]) != `"conflict"` ||
		string(body["saveType"]) != `"pc"` || string(body["conflictCount"]) != "1" ||
		string(body["restoreBlocked"]) != "true" {
		t.Fatalf("status response = %d %+v", response.StatusCode, body)
	}

	if response, _ := ts.do(t, http.MethodPost, "/api/cloud/restore/"+gameID, map[string]string{
		"fileName": archiveName, "id": "pc",
	}); response.StatusCode != http.StatusConflict {
		t.Errorf("ordinary restore status = %d, want 409", response.StatusCode)
	}
	if response, _ := ts.do(t, http.MethodPost, "/api/cloud/sync-local/"+gameID, nil); response.StatusCode != http.StatusConflict {
		t.Errorf("publish during conflict status = %d, want 409", response.StatusCode)
	}
	if scopeReads != 3 || writes != 0 {
		t.Fatalf("status requests = %d, unexpected writes = %d", scopeReads, writes)
	}
}

func TestNativeCloudRestoreOnlyAcceptsAuthoritativeHead(t *testing.T) {
	ts := startTestServer(t)
	const gameID = "same-game"

	var scopeReads, nonStatusRequests int
	if err := ts.daemon.Store.CreateGame(store.Game{
		ID: gameID, Name: "Same Game", SavePath: ts.saveDir, SaveType: "pc",
	}); err != nil {
		t.Fatal(err)
	}
	settings, err := ts.daemon.Store.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	const localSnapshot = "snap_2"
	headID := cloud.GameSaveSnapshotID(localSnapshot, settings.NodeID)
	headArchive := cloud.GameSaveArchiveName(gameID, "main", localSnapshot, settings.NodeID)
	oldArchive := "same-game__main__snap_1--dev-desktop.zip"
	manifest, _, err := delta.BuildMultiManifest(ts.saveDir, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := ts.daemon.Store.SetLastManifestHash(gameID, watcher.ContentHash(manifest, "")); err != nil {
		t.Fatal(err)
	}
	if err := ts.daemon.Store.SetCloudHead(store.CloudHead{
		GameID: gameID, Snapshot: localSnapshot, NativeHead: headID, File: headArchive,
	}); err != nil {
		t.Fatal(err)
	}

	service := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.Method == http.MethodGet && r.URL.Path == "/v1/opensave/scopes/pc/"+gameID {
			scopeReads++
			now := time.Now().UTC()
			old := cloud.GameSaveScopeSnapshot{
				GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
					ID: "snap_1--dev-desktop", Archive: oldArchive, CreatedAt: now.Add(-time.Minute),
				},
				DeviceName: "Desktop", ReceivedAt: now.Add(-time.Minute),
			}
			head := cloud.GameSaveScopeSnapshot{
				GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
					ID: headID, Archive: headArchive, Parent: old.ID, CreatedAt: now,
				},
				DeviceName: "Desktop", ReceivedAt: now,
			}
			_ = json.NewEncoder(w).Encode(cloud.GameSaveScope{
				Type: "pc", GameID: gameID, Head: &head,
				Snapshots: []cloud.GameSaveScopeSnapshot{head, old},
			})
			return
		}
		nonStatusRequests++
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer service.Close()

	cfg, err := ts.daemon.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	cfg.Enabled, cfg.Provider, cfg.URL, cfg.Password = true, cloud.GameSaveProvider, service.URL, "test-key"
	if err := ts.daemon.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}

	response, body := ts.do(t, http.MethodPost, "/api/cloud/restore/"+gameID, map[string]string{
		"fileName": oldArchive, "id": "pc",
	})
	if response.StatusCode != http.StatusConflict ||
		!strings.Contains(string(body["error"]), "authoritative native game-save cloud head") {
		t.Fatalf("historical native restore = %d %+v, want 409 authoritative-head error", response.StatusCode, body)
	}
	if scopeReads != 1 || nonStatusRequests != 0 {
		t.Fatalf("historical restore requests = scope:%d non-status:%d", scopeReads, nonStatusRequests)
	}
}

func TestNativeCloudPublishReportsCASConflictInsteadOfSuccess(t *testing.T) {
	ts := startTestServer(t)
	const gameID = "same-game"
	if err := ts.daemon.Store.CreateGame(store.Game{
		ID: gameID, Name: "Same Game", SavePath: ts.saveDir, SaveType: "pc",
	}); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ts.saveDir, "save.dat"), []byte("base"), 0o666); err != nil {
		t.Fatal(err)
	}
	base, err := ts.daemon.Snapshots.Create(gameID, "", true)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(ts.saveDir, "save.dat"), []byte("local candidate"), 0o666); err != nil {
		t.Fatal(err)
	}
	current, err := ts.daemon.Snapshots.Create(gameID, "", true)
	if err != nil {
		t.Fatal(err)
	}
	manifest, _, err := delta.BuildMultiManifest(ts.saveDir, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := ts.daemon.Store.SetLastManifestHash(gameID, watcher.ContentHash(manifest, "")); err != nil {
		t.Fatal(err)
	}
	settings, err := ts.daemon.Store.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	baseHead := cloud.GameSaveSnapshotID(base.ID, settings.NodeID)
	baseArchive := cloud.GameSaveArchiveName(gameID, "main", base.ID, settings.NodeID)
	if err := ts.daemon.Store.SetCloudHead(store.CloudHead{
		GameID: gameID, Snapshot: current.ID, NativeHead: baseHead, File: baseArchive,
	}); err != nil {
		t.Fatal(err)
	}

	var scopeReads, archiveWrites, conflictWrites int
	baseRemote := cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
			ID: baseHead, Archive: baseArchive, CreatedAt: time.Now().UTC().Add(-time.Minute),
		},
		DeviceName: "Desktop", ReceivedAt: time.Now().UTC().Add(-time.Minute),
	}
	racedHead := cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
			ID: "snap_remote--dev-handheld", Archive: "same-game__main__snap_remote--dev-handheld.zip",
			Parent: baseHead, CreatedAt: time.Now().UTC(),
		},
		DeviceName: "Handheld", ReceivedAt: time.Now().UTC(),
	}
	var conflict cloud.GameSaveScopeSnapshot
	raced := false
	service := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/opensave/scopes/pc/"+gameID:
			scopeReads++
			response := cloud.GameSaveScope{
				Type: "pc", GameID: gameID, Head: &baseRemote,
				Snapshots: []cloud.GameSaveScopeSnapshot{baseRemote},
			}
			if raced {
				response.Head = &racedHead
				response.Snapshots = []cloud.GameSaveScopeSnapshot{baseRemote, racedHead}
			}
			if conflict.ID != "" {
				response.Conflicts = []cloud.GameSaveScopeSnapshot{conflict}
			}
			_ = json.NewEncoder(w).Encode(response)
		case r.Method == http.MethodPut && strings.HasPrefix(r.URL.Path, "/v1/opensave/files/pc/"):
			archiveWrites++
			w.WriteHeader(http.StatusCreated)
		case r.Method == http.MethodPut && strings.HasPrefix(r.URL.Path, "/v1/opensave/scopes/pc/"+gameID+"/snapshots/"):
			// The server accepted another device's head after OpenSave read the
			// preflight scope. The candidate must become a conflict, not a success.
			raced = true
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusConflict)
			_ = json.NewEncoder(w).Encode(map[string]string{"head": racedHead.ID})
		case r.Method == http.MethodPut && strings.HasPrefix(r.URL.Path, "/v1/opensave/scopes/pc/"+gameID+"/conflicts/"):
			var candidate cloud.GameSaveScopeCandidate
			if err := json.NewDecoder(r.Body).Decode(&candidate); err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			conflict = cloud.GameSaveScopeSnapshot{
				GameSaveScopeCandidate: candidate, Conflict: true, ConflictOf: racedHead.ID,
				DeviceName: "Desktop", ReceivedAt: time.Now().UTC(),
			}
			conflictWrites++
			_ = json.NewEncoder(w).Encode(conflict)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer service.Close()

	cfg, err := ts.daemon.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	cfg.Enabled, cfg.Provider, cfg.URL, cfg.Password = true, cloud.GameSaveProvider, service.URL, "test-key"
	if err := ts.daemon.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}

	response, body := ts.do(t, http.MethodPost, "/api/cloud/sync-local/"+gameID, nil)
	if response.StatusCode != http.StatusConflict ||
		!strings.Contains(string(body["error"]), "retained as a native game-save conflict copy") {
		t.Fatalf("CAS-raced native publish = %d %+v, want 409 conflict result", response.StatusCode, body)
	}
	if scopeReads != 3 || archiveWrites != 1 || conflictWrites != 1 || conflict.ID == "" {
		t.Fatalf("native request counts scope=%d archive=%d conflict=%d record=%+v", scopeReads, archiveWrites, conflictWrites, conflict)
	}
}
