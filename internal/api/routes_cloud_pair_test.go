package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

func setTestCloudConfig(t *testing.T, ts *testServer, mutate func(*store.CloudConfig)) {
	t.Helper()
	cfg, err := ts.daemon.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	mutate(&cfg)
	if err := ts.daemon.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}
}

func decodedString(t *testing.T, body map[string]json.RawMessage, key string) string {
	t.Helper()
	raw, ok := body[key]
	if !ok {
		t.Fatalf("response has no %q: %+v", key, body)
	}
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		t.Fatalf("decode %q: %v", key, err)
	}
	return value
}

func doRaw(t *testing.T, ts *testServer, method, path string, body any) (*http.Response, []byte) {
	t.Helper()
	var raw []byte
	if body != nil {
		var err error
		raw, err = json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
	}
	req, err := http.NewRequest(method, ts.base+path, bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return resp, payload
}

func TestCloudPairPreview(t *testing.T) {
	ts := startTestServer(t)
	link := "savevault://pair?v=1&server=" + url.QueryEscape("https://nas.example:8788/") + "&code=abcd-efgh"

	resp, decoded := ts.do(t, http.MethodPost, "/api/cloud/pair/preview", map[string]any{"link": link})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("link preview status = %d, body = %+v", resp.StatusCode, decoded)
	}
	if got := decodedString(t, decoded, "server"); got != "https://nas.example:8788" {
		t.Errorf("server = %q", got)
	}
	if got := decodedString(t, decoded, "host"); got != "nas.example:8788" {
		t.Errorf("host = %q", got)
	}
	if got := decodedString(t, decoded, "code"); got != "ABCD-EFGH" {
		t.Errorf("code = %q", got)
	}

	resp, decoded = ts.do(t, http.MethodPost, "/api/cloud/pair/preview", map[string]any{
		"server": "https://nas.example:8788/", "code": " ab cd ef gh ",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("field preview status = %d, body = %+v", resp.StatusCode, decoded)
	}
	if got := decodedString(t, decoded, "server"); got != "https://nas.example:8788" {
		t.Errorf("field server = %q", got)
	}
	if got := decodedString(t, decoded, "host"); got != "nas.example:8788" {
		t.Errorf("field host = %q", got)
	}
	if got := decodedString(t, decoded, "code"); got != "ABCD-EFGH" {
		t.Errorf("field code = %q", got)
	}

	resp, decoded = ts.do(t, http.MethodPost, "/api/cloud/pair/preview", map[string]any{
		"server": "https://nas.example:8788", "code": "short",
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("invalid code status = %d, want 400", resp.StatusCode)
	}
	if got := decodedString(t, decoded, "error"); got != cloud.ErrPairingCodeFormat.Error() {
		t.Errorf("invalid code error = %q", got)
	}
}

func TestCloudPairClaimRefusesUnconfirmedHost(t *testing.T) {
	ts := startTestServer(t)
	var requests atomic.Int32
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
	}))
	defer fake.Close()

	resp, decoded := ts.do(t, http.MethodPost, "/api/cloud/pair/claim", map[string]any{
		"server": fake.URL, "code": "abcd-efgh", "confirmedHost": "elsewhere.example",
	})
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", resp.StatusCode)
	}
	if got := decodedString(t, decoded, "error"); got != "confirm the server's host name before pairing" {
		t.Errorf("error = %q", got)
	}
	if n := requests.Load(); n != 0 {
		t.Fatalf("unconfirmed claim made %d request(s)", n)
	}
}

func TestCloudPairClaimStoresTokenWithoutEchoingIt(t *testing.T) {
	const token = "gsdt_0123456789abcdef_0123456789abcdef0123456789abcdef0123456789abcdef"
	const tokenID = "0123456789abcdef"
	var receivedDeviceID string
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/v1/pair/claim" {
			t.Errorf("request = %s %s", r.Method, r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "" {
			t.Errorf("pairing claim sent Authorization %q", got)
		}
		var body struct {
			DeviceID string `json:"device_id"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		receivedDeviceID = body.DeviceID
		_ = json.NewEncoder(w).Encode(map[string]any{
			"token": token, "token_id": tokenID, "device_id": body.DeviceID,
			"name": "书房的电脑", "server_version": "v1.7.0",
		})
	}))
	defer fake.Close()

	ts := startTestServer(t)
	settings, err := ts.daemon.Store.GetSettings()
	if err != nil {
		t.Fatal(err)
	}
	resp, payload := doRaw(t, ts, http.MethodPost, "/api/cloud/pair/claim", map[string]any{
		"server": fake.URL, "code": "abcd-efgh", "confirmedHost": strings.TrimPrefix(fake.URL, "http://"),
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, body = %s", resp.StatusCode, payload)
	}
	if bytes.Contains(payload, []byte(token)) {
		t.Fatalf("claim response contains the device token: %s", payload)
	}
	var decoded map[string]json.RawMessage
	if err := json.Unmarshal(payload, &decoded); err != nil {
		t.Fatal(err)
	}
	if _, exists := decoded["token"]; exists {
		t.Fatalf("claim response has a token field: %s", payload)
	}
	if got := decodedString(t, decoded, "name"); got != "书房的电脑" {
		t.Errorf("name = %q", got)
	}
	if got := decodedString(t, decoded, "tokenId"); got != tokenID {
		t.Errorf("tokenId = %q", got)
	}
	if got := decodedString(t, decoded, "server"); got != fake.URL {
		t.Errorf("server = %q", got)
	}

	cfg, err := ts.daemon.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Password != token {
		t.Fatalf("stored password = %q, want the device token", cfg.Password)
	}
	if cfg.Provider != cloud.GameSaveProvider || cfg.URL != fake.URL || !cfg.Enabled {
		t.Fatalf("stored config = %+v", cfg)
	}
	if cfg.GameSaveAuthBlock != "" || cfg.GameSaveAuthWarning != store.GameSaveAuthWarningNone {
		t.Fatalf("claim left auth state = (%q, %d)", cfg.GameSaveAuthBlock, cfg.GameSaveAuthWarning)
	}
	if receivedDeviceID != settings.NodeID {
		t.Fatalf("submitted device_id = %q, want NodeID %q", receivedDeviceID, settings.NodeID)
	}

	for _, entry := range ts.daemon.Log.History() {
		if strings.Contains(entry.Message, token) {
			t.Fatalf("daemon log contains the device token: %q", entry.Message)
		}
	}
	found := false
	for _, entry := range ts.daemon.Log.History() {
		if strings.Contains(entry.Message, "paired with game-save") &&
			strings.Contains(entry.Message, "书房的电脑") && strings.Contains(entry.Message, tokenID) {
			found = true
		}
	}
	if !found {
		t.Fatal("daemon log has no pairing entry with the device name and token ID")
	}
}

func TestCloudPairClaimStatusMapping(t *testing.T) {
	for _, tc := range []struct {
		name       string
		serverCode int
		wantStatus int
		wantError  string
	}{
		{"unsupported", http.StatusNotFound, http.StatusNotImplemented, "the service does not support pairing"},
		{"invalid", http.StatusUnauthorized, http.StatusBadRequest, "the pairing code is invalid, expired, or already used"},
		{"rate limited", http.StatusTooManyRequests, http.StatusTooManyRequests, "too many failed pairing attempts; try again later"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.serverCode)
			}))
			defer fake.Close()
			ts := startTestServer(t)
			resp, decoded := ts.do(t, http.MethodPost, "/api/cloud/pair/claim", map[string]any{
				"server": fake.URL, "code": "abcd-efgh", "confirmedHost": strings.TrimPrefix(fake.URL, "http://"),
			})
			if resp.StatusCode != tc.wantStatus {
				t.Fatalf("status = %d, want %d", resp.StatusCode, tc.wantStatus)
			}
			if got := decodedString(t, decoded, "error"); got != tc.wantError {
				t.Errorf("error = %q, want %q", got, tc.wantError)
			}
		})
	}
}

func configureAPIGameSave(t *testing.T, ts *testServer, serverURL string) {
	t.Helper()
	setTestCloudConfig(t, ts, func(c *store.CloudConfig) {
		c.Enabled = true
		c.Provider = cloud.GameSaveProvider
		c.URL = serverURL
		c.Password = "sync-key"
	})
}

func TestCloudAuthStatusDeviceAndShared(t *testing.T) {
	t.Run("device", func(t *testing.T) {
		fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/v1/auth/whoami" {
				t.Errorf("path = %q", r.URL.Path)
			}
			_, _ = w.Write([]byte(`{"auth":"device","token_id":"tid","device_id":"dev","name":"书房的电脑","shared_token_mode":"allow"}`))
		}))
		defer fake.Close()
		ts := startTestServer(t)
		configureAPIGameSave(t, ts, fake.URL)

		resp, decoded := ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d", resp.StatusCode)
		}
		if got := decodedString(t, decoded, "state"); got != "device" {
			t.Errorf("state = %q", got)
		}
		if got := decodedString(t, decoded, "name"); got != "书房的电脑" {
			t.Errorf("name = %q", got)
		}
		if got := decodedString(t, decoded, "tokenId"); got != "tid" {
			t.Errorf("tokenId = %q", got)
		}
		if got := decodedString(t, decoded, "sharedTokenMode"); got != "allow" {
			t.Errorf("sharedTokenMode = %q", got)
		}
		if got := decodedString(t, decoded, "error"); got != "" {
			t.Errorf("error = %q", got)
		}
		var warning bool
		_ = json.Unmarshal(decoded["warning"], &warning)
		if warning {
			t.Error("device auth reported a shared-token warning")
		}
	})

	t.Run("shared", func(t *testing.T) {
		fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(`{"auth":"shared","shared_token_mode":"allow"}`))
		}))
		defer fake.Close()
		ts := startTestServer(t)
		configureAPIGameSave(t, ts, fake.URL)

		_, decoded := ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
		if got := decodedString(t, decoded, "state"); got != "shared" {
			t.Errorf("state = %q", got)
		}
		if got := decodedString(t, decoded, "name"); got != "" {
			t.Errorf("name = %q", got)
		}
	})
}

func TestCloudAuthStatusSeesWarningHeaderFromWhoami(t *testing.T) {
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Game-Save-Auth-Warning", "shared-token-deprecated")
		_, _ = w.Write([]byte(`{"auth":"device","token_id":"tid","name":"书房的电脑","shared_token_mode":"warn"}`))
	}))
	defer fake.Close()
	ts := startTestServer(t)
	configureAPIGameSave(t, ts, fake.URL)

	_, decoded := ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
	var warning bool
	_ = json.Unmarshal(decoded["warning"], &warning)
	if !warning {
		t.Fatal("warning header seen during whoami was not reported")
	}
	cfg, _ := ts.daemon.Store.GetCloudConfig()
	if cfg.GameSaveAuthWarning != store.GameSaveAuthWarningPending {
		t.Fatalf("stored warning = %d, want pending", cfg.GameSaveAuthWarning)
	}
}

func TestCloudAuthWarningDismiss(t *testing.T) {
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"auth":"shared","shared_token_mode":"warn"}`))
	}))
	defer fake.Close()
	ts := startTestServer(t)
	configureAPIGameSave(t, ts, fake.URL)

	_, decoded := ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
	if got := decodedString(t, decoded, "state"); got != "shared" {
		t.Fatalf("state = %q", got)
	}
	var warning bool
	_ = json.Unmarshal(decoded["warning"], &warning)
	if !warning {
		t.Fatal("shared+warn did not report a warning")
	}

	resp, _ := ts.do(t, http.MethodPost, "/api/cloud/auth-warning/dismiss", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("dismiss status = %d", resp.StatusCode)
	}
	_, decoded = ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
	_ = json.Unmarshal(decoded["warning"], &warning)
	if warning {
		t.Fatal("warning still reported after dismissal")
	}
}

func TestCloudAuthStatusRevokedDoesNotCallTheService(t *testing.T) {
	var requests atomic.Int32
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
	}))
	defer fake.Close()
	ts := startTestServer(t)
	configureAPIGameSave(t, ts, fake.URL)
	if err := ts.daemon.Store.SetGameSaveAuthBlock("token_revoked"); err != nil {
		t.Fatal(err)
	}

	_, decoded := ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
	if got := decodedString(t, decoded, "state"); got != "revoked" {
		t.Errorf("state = %q", got)
	}
	if n := requests.Load(); n != 0 {
		t.Fatalf("revoked status made %d request(s)", n)
	}
}

func TestCloudAuthStatusUnsupportedAndUnauthorized(t *testing.T) {
	t.Run("unsupported", func(t *testing.T) {
		fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusNotFound)
		}))
		defer fake.Close()
		ts := startTestServer(t)
		configureAPIGameSave(t, ts, fake.URL)
		_, decoded := ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
		if got := decodedString(t, decoded, "state"); got != "unsupported" {
			t.Errorf("state = %q", got)
		}
	})

	t.Run("unauthorized", func(t *testing.T) {
		fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"bad key","code":"unauthorized"}`))
		}))
		defer fake.Close()
		ts := startTestServer(t)
		configureAPIGameSave(t, ts, fake.URL)
		_, decoded := ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
		if got := decodedString(t, decoded, "state"); got != "unauthorized" {
			t.Errorf("state = %q", got)
		}
	})
}

func TestCloudAuthStatusOff(t *testing.T) {
	ts := startTestServer(t)
	_, decoded := ts.do(t, http.MethodGet, "/api/cloud/auth-status", nil)
	if got := decodedString(t, decoded, "state"); got != "off" {
		t.Errorf("state = %q", got)
	}
}

func TestApplyCloudPatchClearsGameSaveAuthState(t *testing.T) {
	t.Run("credentials changed", func(t *testing.T) {
		ts := startTestServer(t)
		setTestCloudConfig(t, ts, func(c *store.CloudConfig) {
			c.Enabled, c.Provider, c.URL, c.Password = true, cloud.GameSaveProvider, "https://old.example", "old-key"
		})
		if err := ts.daemon.Store.SetGameSaveAuthBlock("token_revoked"); err != nil {
			t.Fatal(err)
		}
		if err := ts.daemon.Store.SetGameSaveAuthWarning(store.GameSaveAuthWarningPending); err != nil {
			t.Fatal(err)
		}

		resp, _ := ts.do(t, http.MethodPost, "/api/settings", map[string]any{
			"cloudSync": map[string]any{
				"provider": cloud.GameSaveProvider, "url": "https://new.example", "password": "new-key",
			},
		})
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("settings status = %d", resp.StatusCode)
		}
		cfg, _ := ts.daemon.Store.GetCloudConfig()
		if cfg.URL != "https://new.example" || cfg.Password != "new-key" {
			t.Fatalf("saved config = %+v", cfg)
		}
		if cfg.GameSaveAuthBlock != "" || cfg.GameSaveAuthWarning != store.GameSaveAuthWarningNone {
			t.Fatalf("auth state = (%q, %d), want cleared", cfg.GameSaveAuthBlock, cfg.GameSaveAuthWarning)
		}
	})

	t.Run("provider switched", func(t *testing.T) {
		ts := startTestServer(t)
		setTestCloudConfig(t, ts, func(c *store.CloudConfig) {
			c.Enabled, c.Provider, c.URL, c.Password = true, "local", "https://same.example", "same-key"
		})
		if err := ts.daemon.Store.SetGameSaveAuthBlock("token_revoked"); err != nil {
			t.Fatal(err)
		}
		if err := ts.daemon.Store.SetGameSaveAuthWarning(store.GameSaveAuthWarningPending); err != nil {
			t.Fatal(err)
		}

		resp, _ := ts.do(t, http.MethodPost, "/api/settings", map[string]any{
			"cloudSync": map[string]any{"provider": cloud.GameSaveProvider},
		})
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("settings status = %d", resp.StatusCode)
		}
		cfg, _ := ts.daemon.Store.GetCloudConfig()
		if cfg.Provider != cloud.GameSaveProvider {
			t.Fatalf("provider = %q", cfg.Provider)
		}
		if cfg.GameSaveAuthBlock != "" || cfg.GameSaveAuthWarning != store.GameSaveAuthWarningNone {
			t.Fatalf("auth state = (%q, %d), want cleared", cfg.GameSaveAuthBlock, cfg.GameSaveAuthWarning)
		}
	})
}

func TestGameSaveRevocationStopsAutomaticSync(t *testing.T) {
	var requests atomic.Int32
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"revoked","code":"token_revoked"}`))
	}))
	defer fake.Close()
	ts := startTestServer(t)
	configureAPIGameSave(t, ts, fake.URL)
	if err := ts.daemon.Store.CreateGame(store.Game{
		ID: "revoked-game", Name: "Revoked Game", SavePath: t.TempDir(),
	}); err != nil {
		t.Fatal(err)
	}

	ts.daemon.CheckCloud()
	first := requests.Load()
	if first == 0 {
		t.Fatal("the first cloud check did not reach the service")
	}
	cfg, _ := ts.daemon.Store.GetCloudConfig()
	if cfg.GameSaveAuthBlock != "token_revoked" {
		t.Fatalf("block = %q", cfg.GameSaveAuthBlock)
	}
	if cloud.Ready(cfg) {
		t.Fatal("a revoked config still reports ready")
	}

	ts.daemon.CheckCloud()
	if got := requests.Load(); got != first {
		t.Fatalf("the second cloud check made %d request(s), want none", got-first)
	}
}
