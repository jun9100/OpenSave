package store

import (
	"database/sql"
	"errors"
	"fmt"
)

// CloudScopeGone is this device's durable memory of a game-save server
// tombstone (410 scope_deleted or scope_retired). It is not the untrack
// tombstone in untracked_games: that records a local decision to stop
// following a game, while this records a refusal from one server scope.
// See migrations/0043_cloud_scope_gone.sql.
type CloudScopeGone struct {
	GameID          string `db:"game_id"`
	Code            string `db:"code"`
	GoneAt          string `db:"gone_at"`
	SuccessorType   string `db:"successor_type"`
	SuccessorGameID string `db:"successor_game_id"`
	SuccessorName   string `db:"successor_name"`
	Notice          string `db:"notice"`
	ResumeRefused   bool   `db:"resume_refused"`
	RecordedAt      string `db:"recorded_at"`
}

// GetCloudScopeGone returns the record and false when none exists.
func (s *Store) GetCloudScopeGone(gameID string) (CloudScopeGone, bool, error) {
	var gone CloudScopeGone
	err := s.db.Get(&gone, `SELECT * FROM cloud_scope_gone WHERE game_id = ?`, gameID)
	if errors.Is(err, sql.ErrNoRows) {
		return CloudScopeGone{GameID: gameID}, false, nil
	}
	if err != nil {
		return CloudScopeGone{}, false, fmt.Errorf("get cloud scope gone %s: %w", gameID, err)
	}
	return gone, true, nil
}

// ListCloudScopeGone returns every recorded server tombstone, ordered by
// game id so API output and tests are deterministic.
func (s *Store) ListCloudScopeGone() ([]CloudScopeGone, error) {
	var out []CloudScopeGone
	if err := s.db.Select(&out, `SELECT * FROM cloud_scope_gone ORDER BY game_id`); err != nil {
		return nil, fmt.Errorf("list cloud scope gone: %w", err)
	}
	return out, nil
}

// PutCloudScopeGone inserts or replaces one record. The caller owns the
// notice/resume semantics because they depend on the previous row.
func (s *Store) PutCloudScopeGone(gone CloudScopeGone) error {
	_, err := s.db.NamedExec(`
		INSERT INTO cloud_scope_gone
			(game_id, code, gone_at, successor_type, successor_game_id, successor_name, notice, resume_refused, recorded_at)
		VALUES
			(:game_id, :code, :gone_at, :successor_type, :successor_game_id, :successor_name, :notice, :resume_refused, :recorded_at)
		ON CONFLICT(game_id) DO UPDATE SET
			code = excluded.code,
			gone_at = excluded.gone_at,
			successor_type = excluded.successor_type,
			successor_game_id = excluded.successor_game_id,
			successor_name = excluded.successor_name,
			notice = excluded.notice,
			resume_refused = excluded.resume_refused,
			recorded_at = excluded.recorded_at`, gone)
	if err != nil {
		return fmt.Errorf("put cloud scope gone %s: %w", gone.GameID, err)
	}
	return nil
}

// SetCloudScopeGoneNotice changes only the user-facing notice.
func (s *Store) SetCloudScopeGoneNotice(gameID, notice string) error {
	res, err := s.db.Exec(`UPDATE cloud_scope_gone SET notice = ? WHERE game_id = ?`, notice, gameID)
	if err != nil {
		return fmt.Errorf("set cloud scope gone notice %s: %w", gameID, err)
	}
	return checkRowAffected(res)
}

// DeleteCloudScopeGone drops the record, for example when the game is
// untracked or the user successfully reopens the scope.
func (s *Store) DeleteCloudScopeGone(gameID string) error {
	if _, err := s.db.Exec(`DELETE FROM cloud_scope_gone WHERE game_id = ?`, gameID); err != nil {
		return fmt.Errorf("delete cloud scope gone %s: %w", gameID, err)
	}
	return nil
}
