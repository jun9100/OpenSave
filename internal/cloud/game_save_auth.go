package cloud

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// ErrGameSaveAuthOff means there is no game-save address or sync key to ask
// about. The local API reports this as the "off" auth state rather than an
// error, matching the empty settings page.
var ErrGameSaveAuthOff = errors.New("game-save is not configured")

// ErrGameSaveWhoamiUnsupported is a route-level 404: the service predates
// v1.7.0 and cannot report whether this device or the shared key is in use.
var ErrGameSaveWhoamiUnsupported = errors.New("the service does not support authentication status")

// GameSaveAuthBlockedError is a 401 whose code decides whether the device
// must pair again or the shared key was disabled. An empty or unknown code is
// reported as unauthorized; the token itself is never included.
type GameSaveAuthBlockedError struct {
	Code string
}

func (e *GameSaveAuthBlockedError) Error() string {
	if e == nil {
		return "cloud sync not authenticated"
	}
	return gameSaveBlockMessage(e.Code)
}

func gameSaveBlockMessage(code string) string {
	switch code {
	case "token_revoked":
		return "cloud sync not authenticated: game-save revoked this device's token; pair this device again"
	case "shared_token_disabled":
		return "cloud sync not authenticated: game-save no longer accepts the shared sync key; pair this device"
	default:
		return "cloud sync not authenticated: game-save rejected this device's credentials; pair this device again"
	}
}

// GameSaveWhoami is the service's answer about the current credential. Device
// tokens carry an ID and the bound device; the shared key does not.
type GameSaveWhoami struct {
	Auth            string `json:"auth"`
	TokenID         string `json:"token_id"`
	DeviceID        string `json:"device_id"`
	Name            string `json:"name"`
	SharedTokenMode string `json:"shared_token_mode"`
}

// GameSaveWhoami asks the service whether this client is using a device token
// or the shared sync key. It is a read-only request: no device headers, no
// heartbeat, and no writes on the service.
func (s *Service) GameSaveWhoami() (GameSaveWhoami, error) {
	cfg, err := s.Store.GetCloudConfig()
	if err != nil {
		return GameSaveWhoami{}, err
	}
	if cfg.Provider != GameSaveProvider || strings.TrimSpace(cfg.URL) == "" || strings.TrimSpace(cfg.Password) == "" {
		return GameSaveWhoami{}, ErrGameSaveAuthOff
	}
	if cfg.GameSaveAuthBlock != "" {
		return GameSaveWhoami{}, &GameSaveAuthBlockedError{Code: cfg.GameSaveAuthBlock}
	}
	normalized, err := NormalizeGameSaveURL(cfg.URL)
	if err != nil {
		return GameSaveWhoami{}, err
	}
	cfg.URL = normalized
	p := gameSave{s: s, cfg: cfg}

	req, err := http.NewRequest(http.MethodGet, strings.TrimRight(normalized, "/")+"/v1/auth/whoami", nil)
	if err != nil {
		return GameSaveWhoami{}, err
	}
	if err := p.authorizeReadOnly(req); err != nil {
		return GameSaveWhoami{}, err
	}
	resp, err := p.do(req)
	if err != nil {
		return GameSaveWhoami{}, err
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusNotFound:
		return GameSaveWhoami{}, ErrGameSaveWhoamiUnsupported
	case http.StatusUnauthorized:
		var payload struct {
			Code string `json:"code"`
		}
		_ = json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&payload)
		return GameSaveWhoami{}, &GameSaveAuthBlockedError{Code: payload.Code}
	}
	if err := transferOK(resp); err != nil {
		return GameSaveWhoami{}, err
	}
	var who GameSaveWhoami
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&who); err != nil {
		return GameSaveWhoami{}, fmt.Errorf("decode game-save authentication status: %w", err)
	}
	if who.Auth == "shared" && who.SharedTokenMode == "warn" {
		_, _ = s.Store.MarkGameSaveAuthWarningSeen()
	}
	return who, nil
}

// doGameSave issues one game-save request and observes its authentication
// headers before the caller reads the body.
func (s *Service) doGameSave(req *http.Request) (*http.Response, error) {
	resp, err := s.httpClient().Do(req)
	if err != nil {
		return nil, err
	}
	s.observeGameSaveResponse(resp)
	return resp, nil
}

func (p gameSave) do(req *http.Request) (*http.Response, error) {
	return p.s.doGameSave(req)
}

// transferGameSave is doGameSave with the per-request deadline used for large
// uploads and downloads.
func (s *Service) transferGameSave(req *http.Request) (*http.Response, error) {
	resp, err := s.doTransfer(req)
	if err != nil {
		return nil, err
	}
	s.observeGameSaveResponse(resp)
	return resp, nil
}

func (p gameSave) transfer(req *http.Request) (*http.Response, error) {
	return p.s.transferGameSave(req)
}

// fetchToFile is the observed equivalent of Service.fetchToFile. The cancel
// tied to the original body survives the body replacement in observation.
func (p gameSave) fetchToFile(req *http.Request, localPath string) error {
	resp, err := p.transfer(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if err := transferOK(resp); err != nil {
		return err
	}
	return writeFileWhole(localPath, func(w io.Writer) error {
		_, err := io.Copy(w, resp.Body)
		return err
	})
}

// observeGameSaveResponse records the compatibility warning and turns a
// terminal 401 into a durable block. The 401 body is read (bounded) and put
// back so the caller's usual error handling still sees it; closing the
// replacement still closes the original body and releases its transfer
// deadline.
func (s *Service) observeGameSaveResponse(resp *http.Response) {
	if resp == nil {
		return
	}
	if strings.Contains(resp.Header.Get("X-Game-Save-Auth-Warning"), "shared-token-deprecated") {
		if changed, err := s.Store.MarkGameSaveAuthWarningSeen(); err == nil && changed {
			s.logf("info", "game-save: the service will stop accepting the shared sync key; pair this device")
		}
	}
	if resp.StatusCode != http.StatusUnauthorized || resp.Body == nil {
		return
	}
	raw, readErr := io.ReadAll(io.LimitReader(resp.Body, 4096))
	resp.Body = &replayedBody{reader: bytes.NewReader(raw), closer: resp.Body}
	if readErr != nil {
		return
	}
	var payload struct {
		Error string `json:"error"`
		Code  string `json:"code"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return
	}
	switch payload.Code {
	case "token_revoked", "shared_token_disabled":
		sent := ""
		if resp.Request != nil {
			sent = resp.Request.Header.Get("Authorization")
		}
		s.recordGameSaveAuthBlock(payload.Code, sent)
	}
}

// recordGameSaveAuthBlock stores a terminal refusal, but only for the key the
// refused request actually carried: a request still in flight with the old
// key must not block a token the user has just paired.
func (s *Service) recordGameSaveAuthBlock(code, sentAuthorization string) {
	s.gameSaveAuthMu.Lock()
	defer s.gameSaveAuthMu.Unlock()
	cfg, err := s.Store.GetCloudConfig()
	if err != nil || cfg.GameSaveAuthBlock == code {
		return
	}
	if sentAuthorization != "Bearer "+strings.TrimSpace(cfg.Password) {
		return
	}
	if err := s.Store.SetGameSaveAuthBlock(code); err != nil {
		return
	}
	s.logf("warn", "game-save: automatic sync stopped — "+gameSaveBlockMessage(code))
}

func (s *Service) logf(level, msg string) {
	if s.Log != nil {
		s.Log(level, msg)
	}
}

// replayedBody serves the bytes already read from a response while keeping
// the original Close, which is where transfer cancellation lives.
type replayedBody struct {
	reader io.Reader
	closer io.Closer
}

func (b *replayedBody) Read(p []byte) (int, error) { return b.reader.Read(p) }
func (b *replayedBody) Close() error               { return b.closer.Close() }
