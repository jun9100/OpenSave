package daemon

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

// ErrGameSaveScopeSyncStopped is returned when this device has recorded a
// server tombstone for a scope. It is a deliberate stop, not a failed upload:
// callers must not log it as an error or put the archive into the retry queue.
var ErrGameSaveScopeSyncStopped = errors.New("cloud sync stopped for this game: its cloud save was deleted or merged")

// GameSaveScopeGoneStatus is the local, network-free description of a scope
// the server refused with 410. At is RFC3339; successor fields only carry
// values for scope_retired.
type GameSaveScopeGoneStatus struct {
	Code                 string `json:"code"`
	At                   string `json:"at"`
	SuccessorType        string `json:"successorType,omitempty"`
	SuccessorGameID      string `json:"successorGameId,omitempty"`
	SuccessorName        string `json:"successorName,omitempty"`
	SuccessorLocalGameID string `json:"successorLocalGameId,omitempty"`
	Notice               string `json:"notice"`
	ResumeRefused        bool   `json:"resumeRefused"`
}

// gameSaveScopeGone looks up the local stop record. A database read failure
// is treated as "no record" so a transient local problem cannot permanently
// suppress synchronization; the next server request will re-establish the
// tombstone.
func (d *Daemon) gameSaveScopeGone(gameID string) (store.CloudScopeGone, bool) {
	record, found, err := d.Store.GetCloudScopeGone(gameID)
	if err != nil {
		if d.Log != nil {
			d.Log.Log("warn", fmt.Sprintf("cloud: could not read stopped game-save scope %q: %v", gameID, err))
		}
		return store.CloudScopeGone{}, false
	}
	return record, found
}

// NoteGameSaveScopeGone is the API's reopen path into the same recording
// logic used by synchronization. It is exported only because the HTTP layer
// lives in another package.
func (d *Daemon) NoteGameSaveScopeGone(game store.Game, gone *cloud.GameSaveScopeGoneError, resumeRefused bool) {
	d.noteGameSaveScopeGone(game, gone, resumeRefused)
}

// noteGameSaveScopeGone records a server tombstone and clears the local
// confirmed head. It does not touch local snapshots or save files. notice
// survives ordinary re-observations; a refused resume deliberately puts it
// back to pending so the user sees the banner again.
func (d *Daemon) noteGameSaveScopeGone(game store.Game, gone *cloud.GameSaveScopeGoneError, resumeRefused bool) {
	if gone == nil {
		return
	}
	now := time.Now().UTC()

	d.cloudRd.heads.Lock()
	record, found, err := d.Store.GetCloudHead(game.ID)
	if err == nil && found {
		record.NativeHead = ""
		if err := d.Store.SetCloudHead(record); err != nil && d.Log != nil {
			d.Log.Log("warn", fmt.Sprintf("cloud: could not clear native game-save head for %q: %v", game.ID, err))
		}
	}
	d.cloudRd.heads.Unlock()

	existing, hadExisting, err := d.Store.GetCloudScopeGone(game.ID)
	if err != nil && d.Log != nil {
		d.Log.Log("warn", fmt.Sprintf("cloud: could not read cloud scope gone record for %q: %v", game.ID, err))
	}
	if err != nil {
		hadExisting = false
		existing = store.CloudScopeGone{}
	}

	goneAt := now.Format(time.RFC3339)
	if gone.Code == "scope_deleted" && !gone.DeletedAt.IsZero() {
		goneAt = gone.DeletedAt.UTC().Format(time.RFC3339Nano)
	}
	if hadExisting && existing.Code == gone.Code && existing.GoneAt != "" {
		goneAt = existing.GoneAt
	}

	notice := "pending"
	if hadExisting && existing.Notice != "" {
		notice = existing.Notice
	}
	if resumeRefused {
		notice = "pending"
	}

	successorName := ""
	successorType := cloud.SaveTypeScope(gone.SuccessorType)
	successorGameID := strings.TrimSpace(gone.SuccessorGameID)
	if gone.Code == "scope_retired" && successorGameID != "" {
		successorName = d.gameSaveSuccessorName(successorType, successorGameID)
	}

	next := store.CloudScopeGone{
		GameID:          game.ID,
		Code:            gone.Code,
		GoneAt:          goneAt,
		SuccessorType:   gone.SuccessorType,
		SuccessorGameID: gone.SuccessorGameID,
		SuccessorName:   successorName,
		Notice:          notice,
		ResumeRefused:   resumeRefused || (hadExisting && existing.ResumeRefused),
		RecordedAt:      now.Format(time.RFC3339),
	}
	if err := d.Store.PutCloudScopeGone(next); err != nil {
		if d.Log != nil {
			d.Log.Log("warn", fmt.Sprintf("cloud: could not record stopped game-save scope %q: %v", game.ID, err))
		}
		return
	}

	if (hadExisting && existing.Code == gone.Code) || d.Log == nil {
		return
	}
	switch gone.Code {
	case "scope_retired":
		if successorName == "" {
			successorName = successorGameID
		}
		d.Log.Log("info", fmt.Sprintf(
			"cloud: %q was merged into %q on the game-save server; cloud sync for it has stopped (local saves untouched)",
			game.Name, successorName))
	default:
		d.Log.Log("info", fmt.Sprintf(
			"cloud: %q was deleted on the game-save server; cloud sync for it has stopped (local saves untouched)",
			game.Name))
	}
}

// gameSaveSuccessorName prefers a title this device tracks, then the name of
// the server-side group holding the successor. A failed group listing is
// harmless: the id remains a usable display fallback.
func (d *Daemon) gameSaveSuccessorName(successorType, successorGameID string) string {
	if successor, err := d.Store.GetGame(successorGameID); err == nil &&
		cloud.SaveTypeScope(successor.SaveType) == cloud.SaveTypeScope(successorType) {
		return successor.Name
	}
	if games, err := d.Cloud.ListGameSaveGames(); err == nil {
		for _, group := range games {
			for _, member := range group.Scopes {
				if member.Type == successorType && member.GameID == successorGameID && group.Name != "" {
					return group.Name
				}
			}
		}
	}
	return successorGameID
}

// applyGameSaveScopeGone turns a local stop record into the status response.
// It is called before any scope request, so the status page works while the
// service is offline and cannot re-create a deleted scope.
func (d *Daemon) applyGameSaveScopeGone(status *GameSaveScopeStatus, record store.CloudScopeGone) {
	gone := d.gameSaveScopeGoneStatus(record)
	status.NativeSupported = true
	status.State = record.Code
	status.Gone = &gone
	status.RestoreBlocked = false
	status.Publishable = false
	switch record.Code {
	case gameSaveStatusScopeDeleted:
		status.Detail = "The cloud save for this game was deleted on the server; cloud sync has stopped."
	case gameSaveStatusScopeRetired:
		status.Detail = "The cloud save for this game was merged into another game; cloud sync has stopped."
	default:
		status.Detail = "The cloud service refused this game's save scope; cloud sync has stopped."
	}
}

// gameSaveScopeGoneStatus builds the API view. successorLocalGameId is
// derived from this device's games only, never from the network.
func (d *Daemon) gameSaveScopeGoneStatus(record store.CloudScopeGone) GameSaveScopeGoneStatus {
	gone := GameSaveScopeGoneStatus{
		Code:            record.Code,
		At:              record.GoneAt,
		SuccessorType:   record.SuccessorType,
		SuccessorGameID: record.SuccessorGameID,
		SuccessorName:   record.SuccessorName,
		Notice:          record.Notice,
		ResumeRefused:   record.ResumeRefused,
	}
	if record.Code == "scope_retired" {
		if successor, err := d.Store.GetGame(record.SuccessorGameID); err == nil &&
			cloud.SaveTypeScope(successor.SaveType) == cloud.SaveTypeScope(record.SuccessorType) {
			gone.SuccessorLocalGameID = successor.ID
		}
	}
	return gone
}
