package cloud

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
)

func TestNormalizePairingCode(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want string
	}{
		{"abcd-efgh", "ABCDEFGH"},
		{" ab cd ef gh ", "ABCDEFGH"},
		{"abcd efgh", "ABCDEFGH"},
		{"OIL0ABCD", "0110ABCD"},
		{"oi l0 ab cd", "0110ABCD"},
	} {
		got, err := NormalizePairingCode(tc.in)
		if err != nil || got != tc.want {
			t.Errorf("NormalizePairingCode(%q) = %q, %v; want %q", tc.in, got, err, tc.want)
		}
	}

	for _, raw := range []string{"ABCD-EFG", "ABCDEFGU", "ABCD-EFG!", "", "ABCD_EFGH"} {
		if _, err := NormalizePairingCode(raw); !errors.Is(err, ErrPairingCodeFormat) {
			t.Errorf("NormalizePairingCode(%q) error = %v, want ErrPairingCodeFormat", raw, err)
		}
	}
}

func TestFormatPairingCode(t *testing.T) {
	for _, tc := range []struct{ in, want string }{
		{"abcdefgh", "ABCD-EFGH"},
		{"OIL0ABCD", "0110-ABCD"},
		{" abcd-efgh ", "ABCD-EFGH"},
	} {
		if got := FormatPairingCode(tc.in); got != tc.want {
			t.Errorf("FormatPairingCode(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestParsePairLink(t *testing.T) {
	link := func(scheme, host, server, code, version string) string {
		q := url.Values{}
		if version != "" {
			q.Set("v", version)
		}
		q.Set("server", server)
		q.Set("code", code)
		return scheme + "://" + host + "?" + q.Encode()
	}

	server, code, err := ParsePairLink(link("savevault", "pair", "https://nas.example:8788/", "abcd-efgh", "1"))
	if err != nil || server != "https://nas.example:8788" || code != "ABCDEFGH" {
		t.Fatalf("ParsePairLink = %q, %q, %v", server, code, err)
	}

	server, code, err = ParsePairLink(link("SAVEVAULT", "PAIR", "https://nas.example:8788", "ABCD EFGH", ""))
	if err != nil || server != "https://nas.example:8788" || code != "ABCDEFGH" {
		t.Fatalf("uppercase ParsePairLink = %q, %q, %v", server, code, err)
	}

	if _, _, err := ParsePairLink(link("savevault", "pair", "https://nas.example", "", "1")); !errors.Is(err, ErrPairingCodeFormat) {
		t.Errorf("missing code error = %v, want ErrPairingCodeFormat", err)
	}
	if _, _, err := ParsePairLink(link("savevault", "pair", "ftp://nas.example", "abcdefgh", "1")); err == nil ||
		!strings.Contains(err.Error(), "http or https") {
		t.Errorf("ftp server error = %v, want an http/https refusal", err)
	}
	if _, _, err := ParsePairLink(link("savevault", "pair", "https://nas.example", "abcdefgh", "2")); !errors.Is(err, ErrNotPairingLink) {
		t.Errorf("v=2 error = %v, want ErrNotPairingLink", err)
	}
	if _, _, err := ParsePairLink("savevault://pair?v=&server=" + url.QueryEscape("https://nas.example") + "&code=abcdefgh"); !errors.Is(err, ErrNotPairingLink) {
		t.Errorf("empty v error = %v, want ErrNotPairingLink", err)
	}
	if _, _, err := ParsePairLink(link("https", "example.com", "https://nas.example", "abcdefgh", "1")); !errors.Is(err, ErrNotPairingLink) {
		t.Errorf("non-savevault link error = %v, want ErrNotPairingLink", err)
	}
	if _, _, err := ParsePairLink(link("savevault", "pair", "", "abcdefgh", "1")); err == nil {
		t.Error("empty server accepted")
	}
}

func TestClaimPairingSendsCodeAndIdentityWithoutCredentials(t *testing.T) {
	const token = "gsdt_0123456789abcdef_0123456789abcdef0123456789abcdef0123456789abcdef"
	const deviceID = "device-1"
	var got struct {
		method      string
		path        string
		contentType string
		auth        string
		deviceID    string
		body        map[string]any
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got.method, got.path = r.Method, r.URL.Path
		got.contentType = r.Header.Get("Content-Type")
		got.auth = r.Header.Get("Authorization")
		got.deviceID = r.Header.Get("X-OpenSave-Device-ID")
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &got.body)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"token":          token,
			"token_id":       "0123456789abcdef",
			"device_id":      deviceID,
			"name":           "书房的电脑",
			"server_version": "v1.7.0",
		})
	}))
	defer server.Close()

	svc, _ := newTestService(t)
	result, err := svc.ClaimPairing(context.Background(), server.URL, "abcd-efgh", PairDevice{
		DeviceID:   deviceID,
		DeviceName: "OpenSave linux",
		Platform:   "opensave-linux",
		Version:    "2.6.0",
	})
	if err != nil {
		t.Fatal(err)
	}
	if got.method != http.MethodPost || got.path != "/v1/pair/claim" {
		t.Fatalf("request = %s %s", got.method, got.path)
	}
	if got.contentType != "application/json" {
		t.Fatalf("Content-Type = %q", got.contentType)
	}
	if got.auth != "" || got.deviceID != "" {
		t.Fatalf("pairing request carried credentials: Authorization=%q device=%q", got.auth, got.deviceID)
	}
	if got.body["code"] != "ABCD-EFGH" || got.body["device_id"] != deviceID {
		t.Fatalf("request body = %+v", got.body)
	}
	if got.body["device_name"] != "OpenSave linux" || got.body["platform"] != "opensave-linux" || got.body["version"] != "2.6.0" {
		t.Fatalf("request body = %+v", got.body)
	}
	if result.Token != token || result.TokenID != "0123456789abcdef" ||
		result.DeviceID != deviceID || result.Name != "书房的电脑" || result.ServerVersion != "v1.7.0" {
		t.Fatalf("result = %+v", result)
	}
}

func TestClaimPairingStatusMapping(t *testing.T) {
	for _, tc := range []struct {
		status int
		want   error
	}{
		{http.StatusUnauthorized, ErrPairingInvalid},
		{http.StatusNotFound, ErrPairingUnsupported},
		{http.StatusTooManyRequests, ErrPairingRateLimited},
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(`{"error":"no"}`))
		}))
		svc, _ := newTestService(t)
		_, err := svc.ClaimPairing(context.Background(), server.URL, "abcdefgh", PairDevice{DeviceID: "d"})
		server.Close()
		if !errors.Is(err, tc.want) {
			t.Errorf("status %d error = %v, want %v", tc.status, err, tc.want)
		}
	}
}

func TestClaimPairingDoesNotFollowRedirects(t *testing.T) {
	var followed atomic.Int32
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/pair/claim", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/followed", http.StatusFound)
	})
	mux.HandleFunc("/followed", func(w http.ResponseWriter, r *http.Request) {
		followed.Add(1)
	})
	server := httptest.NewServer(mux)
	defer server.Close()

	svc, _ := newTestService(t)
	if _, err := svc.ClaimPairing(context.Background(), server.URL, "abcdefgh", PairDevice{DeviceID: "d"}); err == nil {
		t.Fatal("redirect response accepted")
	}
	if n := followed.Load(); n != 0 {
		t.Fatalf("redirect was followed %d time(s)", n)
	}
}

func TestClaimPairingRejectsUnexpectedTokenOrDevice(t *testing.T) {
	for _, tc := range []struct {
		name     string
		response map[string]any
		want     string
	}{
		{"no token", map[string]any{"token": "", "device_id": "d"}, "the service returned no device token"},
		{"whitespace token", map[string]any{"token": "gsdt_a b", "device_id": "d"}, "the service returned no device token"},
		{"non-ASCII token", map[string]any{"token": "gsdt_é", "device_id": "d"}, "the service returned no device token"},
		{"other device", map[string]any{"token": "gsdt_abc", "device_id": "other"}, "the service bound the token to a different device"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_ = json.NewEncoder(w).Encode(tc.response)
			}))
			defer server.Close()
			svc, _ := newTestService(t)
			_, err := svc.ClaimPairing(context.Background(), server.URL, "abcdefgh", PairDevice{DeviceID: "d"})
			if err == nil || err.Error() != tc.want {
				t.Fatalf("error = %v, want %q", err, tc.want)
			}
		})
	}
}
