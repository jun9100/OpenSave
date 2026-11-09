package api

import (
	"errors"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

// scopeGoneItem is the local API view of store.CloudScopeGone. The JSON
// names are the C6 contract, not the database columns.
type scopeGoneItem struct {
	GameID               string `json:"gameId"`
	GameName             string `json:"gameName"`
	Code                 string `json:"code"`
	At                   string `json:"at"`
	SuccessorType        string `json:"successorType,omitempty"`
	SuccessorGameID      string `json:"successorGameId,omitempty"`
	SuccessorName        string `json:"successorName,omitempty"`
	SuccessorLocalGameID string `json:"successorLocalGameId,omitempty"`
	Notice               string `json:"notice"`
	ResumeRefused        bool   `json:"resumeRefused"`
}

// gameSaveScopeTypesForDeletedGame finds a type for a game whose local row is
// already gone. The grouping index is authoritative for retired scopes; the
// fixed namespaces are only a conservative fallback for a deleted scope that
// no longer appears there.
func (s *Server) gameSaveScopeTypesForDeletedGame(gameID string) []string {
	seen := map[string]bool{}
	var found []string
	if games, err := s.Daemon.Cloud.ListGameSaveGames(); err == nil {
		for _, group := range games {
			for _, member := range group.Scopes {
				if member.GameID == gameID && member.Type != "" && !seen[member.Type] {
					seen[member.Type] = true
					found = append(found, member.Type)
				}
			}
		}
	}
	if len(found) > 0 {
		return found
	}
	return []string{"pc", "switch", "emulator-ppsspp", "emulator-generic"}
}

func (s *Server) handleCloudScopeGoneList(w http.ResponseWriter, r *http.Request) {
	records, err := s.Daemon.Store.ListCloudScopeGone()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	items := make([]scopeGoneItem, 0, len(records))
	for _, record := range records {
		item := scopeGoneItem{
			GameID:          record.GameID,
			GameName:        record.GameID,
			Code:            record.Code,
			At:              record.GoneAt,
			SuccessorType:   record.SuccessorType,
			SuccessorGameID: record.SuccessorGameID,
			SuccessorName:   record.SuccessorName,
			Notice:          record.Notice,
			ResumeRefused:   record.ResumeRefused,
		}
		if game, gameErr := s.Daemon.Store.GetGame(record.GameID); gameErr == nil {
			item.GameName = game.Name
		}
		if record.Code == "scope_retired" {
			if successor, successorErr := s.Daemon.Store.GetGame(record.SuccessorGameID); successorErr == nil &&
				cloud.SaveTypeScope(successor.SaveType) == cloud.SaveTypeScope(record.SuccessorType) {
				item.SuccessorLocalGameID = successor.ID
			}
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (s *Server) handleCloudScopeGoneOff(w http.ResponseWriter, r *http.Request) {
	gameID := chi.URLParam(r, "gameId")
	if err := s.Daemon.Store.SetCloudScopeGoneNotice(gameID, "off"); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, err.Error())
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{})
}

func (s *Server) handleCloudScopeGoneResume(w http.ResponseWriter, r *http.Request) {
	gameID := chi.URLParam(r, "gameId")
	game, err := s.Daemon.Store.GetGame(gameID)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	// The user's reopen takes effect unless the immediate read proves the
	// server still refuses the scope. A missing local row is fine: resume is
	// also useful after a previous attempt failed before recording anything.
	if err := s.Daemon.Store.DeleteCloudScopeGone(game.ID); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	_, err = s.Daemon.Cloud.GetGameSaveScope(game.SaveType, game.ID)
	if gone, ok := cloud.IsGameSaveScopeGone(err); ok {
		s.Daemon.NoteGameSaveScopeGone(game, gone, true)
		message := "the cloud still marks this game's save as deleted; clear the deletion mark on the cloud web page first"
		if gone.Code == "scope_retired" {
			message = "the cloud still marks this game's save as merged into another game; undo the merge on the cloud web page first"
		}
		writeError(w, http.StatusConflict, message)
		return
	}
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	status, err := s.Daemon.GameSaveStatus(game.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"state": status.State})
}
