package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/daemon"
	"github.com/opensave/opensave/internal/store"
)

func TestCloudGameGroupRoutesReadOtherDeviceVersions(t *testing.T) {
	ts := startTestServer(t)
	const (
		gameID = "switch-0100000000010000"
		member = "android-switch-ab12"
	)
	base := time.Date(2026, 11, 8, 10, 0, 0, 0, time.UTC)
	head := cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
			ID: "remote-head", Archive: "remote-head.zip", CreatedAt: base,
		},
		DeviceName: "Pixel 8", Platform: "android", ReceivedAt: base,
	}
	old := cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
			ID: "remote-old", Archive: "remote-old.zip", Parent: "remote-head", CreatedAt: base.Add(-time.Hour),
		},
		DeviceName: "Pixel 8", Platform: "android", ReceivedAt: base.Add(-time.Hour),
	}
	localHead := cloud.GameSaveScopeSnapshot{
		GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
			ID: "local-head", Archive: "local-head.zip", CreatedAt: base,
		},
		DeviceName: "Desktop", Platform: "opensave-windows", ReceivedAt: base,
	}

	service := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			t.Errorf("read-only group route sent %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if r.Header.Get("Authorization") != "Bearer test-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/v1/opensave/games":
			_ = json.NewEncoder(w).Encode(map[string]any{"games": []cloud.GameSaveGame{{
				Key: "switch/title:0100000000010000", Type: "switch", Name: "Super Mario Odyssey",
				LastReceivedAt: base,
				Scopes: []cloud.GameSaveGameMember{
					{Type: "switch", GameID: gameID, Link: "auto", Head: &localHead, SnapshotCount: 1, LastReceivedAt: base},
					{Type: "switch", GameID: member, Link: "auto", Name: "Pixel 8", Head: &head, SnapshotCount: 2, LastReceivedAt: base},
				},
			}}})
		case "/v1/opensave/scopes/switch/" + member:
			_ = json.NewEncoder(w).Encode(cloud.GameSaveScope{
				Type: "switch", GameID: member, Head: &head,
				Snapshots: []cloud.GameSaveScopeSnapshot{head, old},
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer service.Close()

	if err := ts.daemon.Store.CreateGame(store.Game{
		ID: gameID, Name: "Super Mario Odyssey", SavePath: ts.saveDir, SaveType: "switch",
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

	response, body := ts.do(t, http.MethodGet, "/api/cloud/game-groups/"+gameID, nil)
	if response.StatusCode != http.StatusOK ||
		string(body["configured"]) != "true" || string(body["supported"]) != "true" ||
		string(body["state"]) != `"ok"` || string(body["gameKey"]) != `"switch/title:0100000000010000"` ||
		string(body["saveType"]) != `"switch"` {
		t.Fatalf("group response = %d %+v", response.StatusCode, body)
	}
	var members []daemon.GameSaveGroupMember
	if err := json.Unmarshal(body["members"], &members); err != nil {
		t.Fatal(err)
	}
	if len(members) != 1 || members[0].GameID != member || members[0].DeviceName != "Pixel 8" ||
		members[0].Platform != "android" || members[0].HeadID != "remote-head" ||
		members[0].HeadArchive != "remote-head.zip" || members[0].SnapshotCount != 2 ||
		!members[0].LastVersionAt.Equal(base) {
		t.Fatalf("group members = %+v", members)
	}

	response, body = ts.do(t, http.MethodGet, "/api/cloud/game-groups/"+gameID+"/members/"+member+"/versions", nil)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("versions status = %d", response.StatusCode)
	}
	var versions []daemon.GameSaveMemberVersion
	if err := json.Unmarshal(body["versions"], &versions); err != nil {
		t.Fatal(err)
	}
	if len(versions) != 2 || versions[0].ID != "remote-head" || !versions[0].IsHead ||
		versions[1].ID != "remote-old" || versions[1].IsHead {
		t.Fatalf("versions = %+v", versions)
	}

	for _, tc := range []struct {
		name string
		path string
		want int
	}{
		{"same scope", "/api/cloud/game-groups/" + gameID + "/members/" + gameID + "/versions", http.StatusBadRequest},
		{"invalid member", "/api/cloud/game-groups/" + gameID + "/members/bad!id/versions", http.StatusBadRequest},
		{"missing scope", "/api/cloud/game-groups/" + gameID + "/members/missing/versions", http.StatusNotFound},
		{"missing game", "/api/cloud/game-groups/missing", http.StatusNotFound},
	} {
		response, _ := ts.do(t, http.MethodGet, tc.path, nil)
		if response.StatusCode != tc.want {
			t.Errorf("%s status = %d, want %d", tc.name, response.StatusCode, tc.want)
		}
	}
}
