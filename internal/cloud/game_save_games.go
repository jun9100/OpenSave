package cloud

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// ErrGameSaveGamesUnsupported is returned only for a route-level 404. An
// older service may still expose scope read/write routes even though it
// cannot group scopes into games, so callers must not infer that from any
// other error.
var ErrGameSaveGamesUnsupported = errors.New("game-save games API is not supported")

// GameSaveGameMember is one scope's summary in the service's game index.
// Head is nil when the scope has never received a snapshot. The summary is
// informational; the scope route remains the authority for lineage.
type GameSaveGameMember struct {
	Type           string                 `json:"type"`
	GameID         string                 `json:"gameId"`
	Link           string                 `json:"link"`
	Name           string                 `json:"name,omitempty"`
	Head           *GameSaveScopeSnapshot `json:"head"`
	SnapshotCount  int                    `json:"snapshotCount"`
	ConflictCount  int                    `json:"conflictCount"`
	LastReceivedAt time.Time              `json:"lastReceivedAt,omitzero"`
	Retired        bool                   `json:"retired,omitempty"`
}

// GameSaveGame groups same-type scopes that hold saves of one game. The
// grouping is a server-side index only: reading it never moves a head.
type GameSaveGame struct {
	Key            string               `json:"key"`
	Type           string               `json:"type"`
	Name           string               `json:"name"`
	Scopes         []GameSaveGameMember `json:"scopes"`
	LastReceivedAt time.Time            `json:"lastReceivedAt,omitzero"`
}

func (p gameSave) gamesEndpoint() string {
	return strings.TrimRight(p.cfg.URL, "/") + "/v1/opensave/games"
}

func (p gameSave) listGames() ([]GameSaveGame, error) {
	req, err := http.NewRequest(http.MethodGet, p.gamesEndpoint(), nil)
	if err != nil {
		return nil, err
	}
	if err := p.authorizeReadOnly(req); err != nil {
		return nil, err
	}
	resp, err := p.do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrGameSaveGamesUnsupported
	}
	if err := transferOK(resp); err != nil {
		return nil, err
	}
	var payload struct {
		Games []GameSaveGame `json:"games"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, fmt.Errorf("decode game-save games: %w", err)
	}
	if payload.Games == nil {
		payload.Games = []GameSaveGame{}
	}
	return payload.Games, nil
}

// ListGameSaveGames reads the service's grouping index. It is a read-only
// request: no device headers are sent and nothing local or remote changes.
func (s *Service) ListGameSaveGames() ([]GameSaveGame, error) {
	p, err := s.gameSaveScopeProvider()
	if err != nil {
		return nil, err
	}
	return p.listGames()
}
