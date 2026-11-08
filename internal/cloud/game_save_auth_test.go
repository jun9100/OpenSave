package cloud

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/opensave/opensave/internal/store"
)

func configureGameSave(t *testing.T, s *store.Store, serverURL string) {
	t.Helper()
	setCloudConfig(t, s, func(c *store.CloudConfig) {
		c.Enabled = true
		c.Provider = GameSaveProvider
		c.URL = serverURL
		c.Password = "sync-key"
	})
}

func TestGameSaveRevokedResponseBlocksFurtherSync(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"this device was revoked","code":"token_revoked"}`))
	}))
	defer server.Close()

	svc, s := newTestService(t)
	configureGameSave(t, s, server.URL)
	var logged []string
	svc.Log = func(level, msg string) { logged = append(logged, level+": "+msg) }

	_, err := svc.List()
	if err == nil || !strings.Contains(err.Error(), "this device was revoked") {
		t.Fatalf("first List error = %v, want the 401 body after observation replayed it", err)
	}
	cfg, err := s.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.GameSaveAuthBlock != "token_revoked" {
		t.Fatalf("block = %q, want token_revoked", cfg.GameSaveAuthBlock)
	}
	if Ready(cfg) {
		t.Fatal("a blocked game-save config still reports ready")
	}
	blockedLogs := 0
	for _, line := range logged {
		if strings.Contains(line, "automatic sync stopped") {
			blockedLogs++
		}
		if strings.Contains(line, "sync-key") {
			t.Fatalf("log contains the sync key: %q", line)
		}
	}
	if blockedLogs != 1 {
		t.Fatalf("block logged %d time(s), want 1: %v", blockedLogs, logged)
	}

	before := requests.Load()
	_, err = svc.List()
	if err == nil || !IsNotConfigured(err) {
		t.Fatalf("second List error = %v, want a not-configured error", err)
	}
	if err.Error() != "cloud sync not authenticated: game-save revoked this device's token; pair this device again" {
		t.Fatalf("second List error text = %q", err)
	}
	if got := requests.Load(); got != before {
		t.Fatalf("second List made %d request(s), want none", got-before)
	}
}

func TestGameSaveUnauthorizedResponseDoesNotBlock(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"bad key","code":"unauthorized"}`))
	}))
	defer server.Close()

	svc, s := newTestService(t)
	configureGameSave(t, s, server.URL)
	if _, err := svc.List(); err == nil {
		t.Fatal("401 response was accepted")
	}
	cfg, err := s.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.GameSaveAuthBlock != "" {
		t.Fatalf("unauthorized response set block %q", cfg.GameSaveAuthBlock)
	}
	if !Ready(cfg) {
		t.Fatal("a 401 without a terminal code made the config not ready")
	}
}

func TestGameSaveWarningHeaderMarksPendingOnce(t *testing.T) {
	var warning atomic.Bool
	warning.Store(true)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if warning.Load() {
			w.Header().Set("X-Game-Save-Auth-Warning", "shared-token-deprecated")
		}
		_, _ = w.Write([]byte(`{"files":[]}`))
	}))
	defer server.Close()

	svc, s := newTestService(t)
	configureGameSave(t, s, server.URL)
	var infoLogs int
	svc.Log = func(level, msg string) {
		if level == "info" && strings.Contains(msg, "shared sync key") {
			infoLogs++
		}
	}

	if _, err := svc.List(); err != nil {
		t.Fatal(err)
	}
	cfg, _ := s.GetCloudConfig()
	if cfg.GameSaveAuthWarning != store.GameSaveAuthWarningPending {
		t.Fatalf("warning = %d, want pending", cfg.GameSaveAuthWarning)
	}
	if infoLogs != 1 {
		t.Fatalf("warning logged %d time(s), want 1", infoLogs)
	}

	if err := s.SetGameSaveAuthWarning(store.GameSaveAuthWarningDismissed); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.List(); err != nil {
		t.Fatal(err)
	}
	cfg, _ = s.GetCloudConfig()
	if cfg.GameSaveAuthWarning != store.GameSaveAuthWarningDismissed {
		t.Fatalf("dismissed warning changed to %d", cfg.GameSaveAuthWarning)
	}
	if infoLogs != 1 {
		t.Fatalf("dismissed warning logged again: %d", infoLogs)
	}
}

func TestGameSaveWhoami(t *testing.T) {
	t.Run("device", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodGet || r.URL.Path != "/v1/auth/whoami" {
				t.Errorf("request = %s %s", r.Method, r.URL.Path)
			}
			if got := r.Header.Get("Authorization"); got != "Bearer sync-key" {
				t.Errorf("Authorization = %q", got)
			}
			if got := r.Header.Get("X-OpenSave-Device-ID"); got != "" {
				t.Errorf("whoami sent a device header: %q", got)
			}
			_, _ = w.Write([]byte(`{"auth":"device","token_id":"tid","device_id":"dev","name":"书房的电脑","shared_token_mode":"allow"}`))
		}))
		defer server.Close()
		svc, s := newTestService(t)
		configureGameSave(t, s, server.URL)
		who, err := svc.GameSaveWhoami()
		if err != nil {
			t.Fatal(err)
		}
		if who.Auth != "device" || who.TokenID != "tid" || who.DeviceID != "dev" || who.Name != "书房的电脑" || who.SharedTokenMode != "allow" {
			t.Fatalf("whoami = %+v", who)
		}
		cfg, _ := s.GetCloudConfig()
		if cfg.GameSaveAuthWarning != store.GameSaveAuthWarningNone {
			t.Fatalf("device token changed warning to %d", cfg.GameSaveAuthWarning)
		}
	})

	t.Run("shared with warn", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(`{"auth":"shared","shared_token_mode":"warn"}`))
		}))
		defer server.Close()
		svc, s := newTestService(t)
		configureGameSave(t, s, server.URL)
		who, err := svc.GameSaveWhoami()
		if err != nil || who.Auth != "shared" || who.SharedTokenMode != "warn" {
			t.Fatalf("whoami = %+v, %v", who, err)
		}
		cfg, _ := s.GetCloudConfig()
		if cfg.GameSaveAuthWarning != store.GameSaveAuthWarningPending {
			t.Fatalf("warning = %d, want pending", cfg.GameSaveAuthWarning)
		}
	})

	t.Run("unsupported", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusNotFound)
		}))
		defer server.Close()
		svc, s := newTestService(t)
		configureGameSave(t, s, server.URL)
		if _, err := svc.GameSaveWhoami(); !errors.Is(err, ErrGameSaveWhoamiUnsupported) {
			t.Fatalf("error = %v, want ErrGameSaveWhoamiUnsupported", err)
		}
	})

	t.Run("401 with terminal code", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"revoked","code":"token_revoked"}`))
		}))
		defer server.Close()
		svc, s := newTestService(t)
		configureGameSave(t, s, server.URL)
		_, err := svc.GameSaveWhoami()
		var blocked *GameSaveAuthBlockedError
		if !errors.As(err, &blocked) || blocked.Code != "token_revoked" {
			t.Fatalf("error = %v, want a token_revoked block", err)
		}
		cfg, _ := s.GetCloudConfig()
		if cfg.GameSaveAuthBlock != "token_revoked" {
			t.Fatalf("block = %q", cfg.GameSaveAuthBlock)
		}
	})

	t.Run("blocked without a request", func(t *testing.T) {
		var requests atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			requests.Add(1)
		}))
		defer server.Close()
		svc, s := newTestService(t)
		configureGameSave(t, s, server.URL)
		if err := s.SetGameSaveAuthBlock("token_revoked"); err != nil {
			t.Fatal(err)
		}
		_, err := svc.GameSaveWhoami()
		var blocked *GameSaveAuthBlockedError
		if !errors.As(err, &blocked) || blocked.Code != "token_revoked" {
			t.Fatalf("error = %v, want a token_revoked block", err)
		}
		if n := requests.Load(); n != 0 {
			t.Fatalf("whoami made %d request(s) while blocked", n)
		}
	})

	t.Run("off", func(t *testing.T) {
		svc, _ := newTestService(t)
		if _, err := svc.GameSaveWhoami(); !errors.Is(err, ErrGameSaveAuthOff) {
			t.Fatalf("error = %v, want ErrGameSaveAuthOff", err)
		}
	})
}

func TestGameSaveDownloadObservesAuthFailure(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"gone","code":"token_revoked"}`))
	}))
	defer server.Close()

	svc, s := newTestService(t)
	configureGameSave(t, s, server.URL)
	err := svc.Download("game__main__snap_1.zip", t.TempDir()+"/out.zip")
	if err == nil || !strings.Contains(err.Error(), "gone") {
		t.Fatalf("Download error = %v", err)
	}
	cfg, _ := s.GetCloudConfig()
	if cfg.GameSaveAuthBlock != "token_revoked" {
		t.Fatalf("block = %q", cfg.GameSaveAuthBlock)
	}
}

func TestGameSaveListDecodesFiles(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"files": []map[string]any{{
			"type": "pc", "name": "game__main__snap_1.zip", "sizeBytes": 12,
			"createdTime": "2026-11-08T12:00:00Z",
		}}})
	}))
	defer server.Close()
	svc, s := newTestService(t)
	configureGameSave(t, s, server.URL)
	files, err := svc.List()
	if err != nil || len(files) != 1 || files[0].ID != "pc" || files[0].Name != "game__main__snap_1.zip" {
		t.Fatalf("List = %+v, %v", files, err)
	}
}
