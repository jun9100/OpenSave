package api

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/daemon"
	"github.com/opensave/opensave/internal/snapshot"
	"github.com/opensave/opensave/internal/store"
)

func TestCrossRestoreErrorStatus(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want int
	}{
		{"in use", daemon.ErrCrossRestoreInUse, http.StatusConflict},
		{"missing", store.ErrNotFound, http.StatusNotFound},
		{"bad request", daemon.ErrCrossRestoreInvalid, http.StatusBadRequest},
		{"bad assignment", snapshot.ErrForeignAssignment, http.StatusBadRequest},
		{"bad archive", snapshot.ErrForeignArchive, http.StatusBadRequest},
		{"other", errors.New("boom"), http.StatusInternalServerError},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := crossRestoreErrorStatus(tc.err); got != tc.want {
				t.Fatalf("crossRestoreErrorStatus(%v) = %d, want %d", tc.err, got, tc.want)
			}
		})
	}
}

func TestCrossRestorePlanRoute(t *testing.T) {
	ts := startTestServer(t)
	if err := ts.daemon.Store.CreateGame(store.Game{
		ID: "cross-game", Name: "Cross Game", SavePath: ts.saveDir, SaveType: "switch",
	}); err != nil {
		t.Fatal(err)
	}

	zipPath := filepath.Join(t.TempDir(), "pc.zip")
	f, err := os.Create(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	w := zip.NewWriter(f)
	entry, err := w.Create("slot1.sav")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := entry.Write([]byte("save")); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(raw)
	archiveName := "cross-game__main__snap_1--dev-source.zip"

	service := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer cross-restore-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/v1/opensave/scopes/switch/member-1":
			_ = json.NewEncoder(w).Encode(cloud.GameSaveScope{
				Type: "switch", GameID: "member-1",
				Snapshots: []cloud.GameSaveScopeSnapshot{{
					GameSaveScopeCandidate: cloud.GameSaveScopeCandidate{
						ID:            "snap_1--dev-source",
						Archive:       archiveName,
						ContentDigest: hex.EncodeToString(digest[:]),
						CreatedAt:     time.Now().UTC(),
					},
					DeviceName: "Pixel 8",
					Platform:   "android",
					ReceivedAt: time.Now().UTC(),
				}},
			})
		case "/v1/opensave/files/switch/" + archiveName:
			_, _ = w.Write(raw)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer service.Close()

	cfg, err := ts.daemon.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	cfg.Enabled = true
	cfg.Provider = cloud.GameSaveProvider
	cfg.URL = service.URL
	cfg.Password = "cross-restore-key"
	if err := ts.daemon.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}

	resp, decoded := ts.do(t, http.MethodPost, "/api/cloud/cross-restore/cross-game/plan", map[string]any{
		"memberGameId": "member-1",
		"snapshotId":   "snap_1--dev-source",
		"unknown":      true,
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("plan status = %d, body = %v", resp.StatusCode, decoded)
	}
	var plan struct {
		Format    string                       `json:"format"`
		Suggested []snapshot.ForeignAssignment `json:"suggested"`
	}
	encoded, _ := json.Marshal(decoded)
	if err := json.Unmarshal(encoded, &plan); err != nil {
		t.Fatal(err)
	}
	if plan.Format != snapshot.ForeignFormatPC {
		t.Fatalf("plan format = %q", plan.Format)
	}
	if len(plan.Suggested) != 1 || plan.Suggested[0].ArchiveLocation != snapshot.ForeignPCRoot ||
		plan.Suggested[0].LocalRoot != "" {
		t.Fatalf("suggested = %+v", plan.Suggested)
	}

	resp, _ = ts.do(t, http.MethodPost, "/api/cloud/cross-restore/cross-game/plan", map[string]string{
		"memberGameId": "../bad",
		"snapshotId":   "snap_1--dev-source",
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("invalid member status = %d, want 400", resp.StatusCode)
	}

	resp, _ = ts.do(t, http.MethodPost, "/api/cloud/cross-restore/missing/plan", map[string]string{
		"memberGameId": "member-1",
		"snapshotId":   "snap_1--dev-source",
	})
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("missing game status = %d, want 404", resp.StatusCode)
	}
}
