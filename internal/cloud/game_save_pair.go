package cloud

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"unicode"
)

// Pairing errors are contract strings: the local API forwards them verbatim
// so the frontend can translate them without parsing server responses.
var (
	ErrPairingCodeFormat  = errors.New("pairing code must be 8 letters or digits, like ABCD-EFGH")
	ErrNotPairingLink     = errors.New("not a SaveVault pairing link")
	ErrPairingUnsupported = errors.New("the service does not support pairing")
	ErrPairingInvalid     = errors.New("the pairing code is invalid, expired, or already used")
	ErrPairingRateLimited = errors.New("too many failed pairing attempts; try again later")
)

// errPairingServerRequired matches NormalizeGameSaveURL's wording for a
// missing host; an empty string normalizes to "" rather than failing there.
var errPairingServerRequired = errors.New("game-save service address must include a host")

// crockfordBase32 is Crockford's alphabet: 0-9 and A-Z without I, L, O, U.
const crockfordBase32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// NormalizePairingCode accepts what a person may type or paste for a pairing
// code and returns the canonical eight characters without the display dash.
func NormalizePairingCode(raw string) (string, error) {
	var compact strings.Builder
	for _, r := range strings.ToUpper(strings.TrimSpace(raw)) {
		if unicode.IsSpace(r) || r == '-' {
			continue
		}
		switch r {
		case 'O':
			r = '0'
		case 'I', 'L':
			r = '1'
		}
		compact.WriteRune(r)
	}
	code := compact.String()
	if len(code) != 8 {
		return "", ErrPairingCodeFormat
	}
	for _, r := range code {
		if !strings.ContainsRune(crockfordBase32, r) {
			return "", ErrPairingCodeFormat
		}
	}
	return code, nil
}

// FormatPairingCode returns the grouped ABCD-EFGH form shown to the user.
// Invalid input is echoed back cleaned up so callers can still display it.
func FormatPairingCode(code string) string {
	normalized, err := NormalizePairingCode(code)
	if err != nil {
		return strings.ToUpper(strings.TrimSpace(code))
	}
	return normalized[:4] + "-" + normalized[4:]
}

// ParsePairLink reads the QR/paste form savevault://pair?v=1&server=...&code=...
// and returns the normalized service address and code.
func ParsePairLink(raw string) (server, code string, err error) {
	parsed, parseErr := url.Parse(strings.TrimSpace(raw))
	if parseErr != nil || parsed == nil ||
		!strings.EqualFold(parsed.Scheme, "savevault") || !strings.EqualFold(parsed.Host, "pair") {
		return "", "", ErrNotPairingLink
	}
	if parsed.Path != "" && parsed.Path != "/" {
		return "", "", ErrNotPairingLink
	}
	query := parsed.Query()
	if versions, present := query["v"]; present {
		if len(versions) != 1 || versions[0] != "1" {
			return "", "", ErrNotPairingLink
		}
	}
	server, err = normalizePairingServer(query.Get("server"))
	if err != nil {
		return "", "", err
	}
	code, err = NormalizePairingCode(query.Get("code"))
	if err != nil {
		return "", "", err
	}
	return server, code, nil
}

func normalizePairingServer(raw string) (string, error) {
	server, err := NormalizeGameSaveURL(raw)
	if err != nil {
		return "", err
	}
	if server == "" {
		return "", errPairingServerRequired
	}
	return server, nil
}

// PairDevice is the identity a client submits while claiming a pairing code.
// DeviceID must be the same ID its later sync requests carry.
type PairDevice struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
	Platform   string `json:"platform"`
	Version    string `json:"version"`
}

// PairResult is the token handed out once by a successful pairing. Token is
// stored as an opaque credential and never logged or returned by our API.
type PairResult struct {
	Token         string `json:"token"`
	TokenID       string `json:"token_id"`
	DeviceID      string `json:"device_id"`
	Name          string `json:"name"`
	ServerVersion string `json:"server_version"`
}

// ClaimPairing exchanges a pairing code for a device token. The request is
// unauthenticated: the one-time code is the credential, and no device header
// may suggest otherwise. Redirects are refused rather than followed so a
// misconfigured address cannot carry the request to another host.
func (s *Service) ClaimPairing(ctx context.Context, server, code string, dev PairDevice) (PairResult, error) {
	server, err := normalizePairingServer(server)
	if err != nil {
		return PairResult{}, err
	}
	code, err = NormalizePairingCode(code)
	if err != nil {
		return PairResult{}, err
	}

	body, err := json.Marshal(struct {
		Code       string `json:"code"`
		DeviceID   string `json:"device_id"`
		DeviceName string `json:"device_name"`
		Platform   string `json:"platform"`
		Version    string `json:"version"`
	}{
		Code:       FormatPairingCode(code),
		DeviceID:   dev.DeviceID,
		DeviceName: dev.DeviceName,
		Platform:   dev.Platform,
		Version:    dev.Version,
	})
	if err != nil {
		return PairResult{}, err
	}

	endpoint := strings.TrimRight(server, "/") + "/v1/pair/claim"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return PairResult{}, err
	}
	req.Header.Set("Content-Type", "application/json")

	// A copy, not the shared client: only this request must refuse redirects.
	client := *s.httpClient()
	client.CheckRedirect = func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}
	resp, err := client.Do(req)
	if err != nil {
		return PairResult{}, err
	}
	defer resp.Body.Close()

	// The status is the contract; a truncated error body must not turn a
	// known refusal into a generic network error.
	switch resp.StatusCode {
	case http.StatusNotFound:
		return PairResult{}, ErrPairingUnsupported
	case http.StatusUnauthorized:
		return PairResult{}, ErrPairingInvalid
	case http.StatusTooManyRequests:
		return PairResult{}, ErrPairingRateLimited
	}
	raw, readErr := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return PairResult{}, fmt.Errorf("HTTP %d - %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	if readErr != nil {
		return PairResult{}, readErr
	}

	var result PairResult
	if err := json.Unmarshal(raw, &result); err != nil {
		return PairResult{}, fmt.Errorf("decode pairing response: %w", err)
	}
	if !validDeviceToken(result.Token) {
		return PairResult{}, errors.New("the service returned no device token")
	}
	if result.DeviceID != "" && result.DeviceID != dev.DeviceID {
		return PairResult{}, errors.New("the service bound the token to a different device")
	}
	return result, nil
}

// validDeviceToken accepts only a non-empty, printable ASCII string with no
// whitespace. Tokens are opaque, but they travel in an HTTP header later.
func validDeviceToken(token string) bool {
	if token == "" {
		return false
	}
	for i := 0; i < len(token); i++ {
		if token[i] <= 0x20 || token[i] >= 0x7f {
			return false
		}
	}
	return true
}
