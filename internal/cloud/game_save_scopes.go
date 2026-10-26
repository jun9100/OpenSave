package cloud

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ErrGameSaveScopesUnsupported is returned only for a route-level 404. A
// client may use the older head-file protocol in that case while a NAS image
// is being upgraded, but must not fall back after authentication, validation,
// or server errors.
var ErrGameSaveScopesUnsupported = errors.New("game-save native scope API is not supported")

// GameSaveScopeConflictError preserves the server's current head when a CAS
// write loses a race. The caller must retain its candidate as a conflict copy,
// rather than retrying it as a head-changing write.
type GameSaveScopeConflictError struct {
	Head string
}

func (e *GameSaveScopeConflictError) Error() string {
	if e == nil || e.Head == "" {
		return "game-save scope head changed"
	}
	return "game-save scope head changed to " + e.Head
}

// GameSaveScopeCandidate is the metadata submitted after its immutable ZIP
// archive has already reached the service.
type GameSaveScopeCandidate struct {
	ID            string    `json:"id"`
	Archive       string    `json:"archive"`
	Parent        string    `json:"parent"`
	ContentDigest string    `json:"contentDigest"`
	CreatedAt     time.Time `json:"createdAt"`
}

// GameSaveScopeSnapshot is the server-audited form of a candidate. Device
// fields are informative; the sync key remains the authorization boundary.
type GameSaveScopeSnapshot struct {
	GameSaveScopeCandidate
	Device     string    `json:"device,omitempty"`
	DeviceName string    `json:"deviceName,omitempty"`
	Platform   string    `json:"platform,omitempty"`
	ReceivedAt time.Time `json:"receivedAt"`
	Conflict   bool      `json:"conflict,omitempty"`
	ConflictOf string    `json:"conflictOf,omitempty"`
}

// GameSaveScope is one typed {saveType}/{gameId} server scope. Head is nil
// for a new scope; that differs from a 404 and proves that CAS is supported.
type GameSaveScope struct {
	Type      string                  `json:"type"`
	GameID    string                  `json:"gameId"`
	Head      *GameSaveScopeSnapshot  `json:"head"`
	Snapshots []GameSaveScopeSnapshot `json:"snapshots"`
	Conflicts []GameSaveScopeSnapshot `json:"conflicts"`
}

func (p gameSave) scopeEndpoint(saveType, gameID string) string {
	return strings.TrimRight(p.cfg.URL, "/") + "/v1/opensave/scopes/" +
		url.PathEscape(SaveTypeScope(saveType)) + "/" + url.PathEscape(gameID)
}

func (p gameSave) getScope(saveType, gameID string) (GameSaveScope, error) {
	req, err := http.NewRequest(http.MethodGet, p.scopeEndpoint(saveType, gameID), nil)
	if err != nil {
		return GameSaveScope{}, err
	}
	if err := p.authorizeReadOnly(req); err != nil {
		return GameSaveScope{}, err
	}
	resp, err := p.s.httpClient().Do(req)
	if err != nil {
		return GameSaveScope{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return GameSaveScope{}, ErrGameSaveScopesUnsupported
	}
	if err := transferOK(resp); err != nil {
		return GameSaveScope{}, err
	}
	var scope GameSaveScope
	if err := json.NewDecoder(resp.Body).Decode(&scope); err != nil {
		return GameSaveScope{}, fmt.Errorf("decode game-save scope: %w", err)
	}
	return scope, nil
}

func (p gameSave) putScopeCandidate(saveType, gameID string, candidate GameSaveScopeCandidate, expectedHead string, conflict bool) (GameSaveScopeSnapshot, error) {
	body, err := json.Marshal(candidate)
	if err != nil {
		return GameSaveScopeSnapshot{}, err
	}
	endpoint := p.scopeEndpoint(saveType, gameID)
	if conflict {
		endpoint += "/conflicts/"
	} else {
		endpoint += "/snapshots/"
	}
	endpoint += url.PathEscape(candidate.ID)
	req, err := http.NewRequest(http.MethodPut, endpoint, bytes.NewReader(body))
	if err != nil {
		return GameSaveScopeSnapshot{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	if !conflict {
		req.Header.Set("If-Match", `"`+expectedHead+`"`)
	}
	if err := p.authorize(req); err != nil {
		return GameSaveScopeSnapshot{}, err
	}
	resp, err := p.s.httpClient().Do(req)
	if err != nil {
		return GameSaveScopeSnapshot{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusConflict {
		var conflictBody struct {
			Head string `json:"head"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&conflictBody)
		return GameSaveScopeSnapshot{}, &GameSaveScopeConflictError{Head: conflictBody.Head}
	}
	if err := transferOK(resp); err != nil {
		return GameSaveScopeSnapshot{}, err
	}
	var snapshot GameSaveScopeSnapshot
	if err := json.NewDecoder(resp.Body).Decode(&snapshot); err != nil {
		return GameSaveScopeSnapshot{}, fmt.Errorf("decode game-save scope snapshot: %w", err)
	}
	return snapshot, nil
}

func (s *Service) gameSaveScopeProvider() (gameSave, error) {
	cfg, err := s.config()
	if err != nil {
		return gameSave{}, err
	}
	if cfg.Provider != GameSaveProvider {
		return gameSave{}, fmt.Errorf("game-save native scopes require the game-save provider")
	}
	if !Ready(cfg) {
		return gameSave{}, notReady(cfg)
	}
	normalized, err := NormalizeGameSaveURL(cfg.URL)
	if err != nil {
		return gameSave{}, err
	}
	cfg.URL = normalized
	return gameSave{s: s, cfg: cfg}, nil
}

// GetGameSaveScope gets the authoritative CAS state for one typed game scope.
func (s *Service) GetGameSaveScope(saveType, gameID string) (GameSaveScope, error) {
	p, err := s.gameSaveScopeProvider()
	if err != nil {
		return GameSaveScope{}, err
	}
	return p.getScope(saveType, gameID)
}

// PutGameSaveScopeSnapshot advances a scope only if expectedHead is still its
// current head. A returned GameSaveScopeConflictError must be handled by
// PutGameSaveScopeConflict instead of being retried as an ordinary write.
func (s *Service) PutGameSaveScopeSnapshot(saveType, gameID string, candidate GameSaveScopeCandidate, expectedHead string) (GameSaveScopeSnapshot, error) {
	p, err := s.gameSaveScopeProvider()
	if err != nil {
		return GameSaveScopeSnapshot{}, err
	}
	return p.putScopeCandidate(saveType, gameID, candidate, expectedHead, false)
}

// PutGameSaveScopeConflict retains a divergent archive without changing the
// current server head.
func (s *Service) PutGameSaveScopeConflict(saveType, gameID string, candidate GameSaveScopeCandidate) (GameSaveScopeSnapshot, error) {
	p, err := s.gameSaveScopeProvider()
	if err != nil {
		return GameSaveScopeSnapshot{}, err
	}
	return p.putScopeCandidate(saveType, gameID, candidate, "", true)
}
