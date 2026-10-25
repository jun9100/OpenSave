package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
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
