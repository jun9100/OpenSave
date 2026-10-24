package store

import "fmt"

// CloudRetry is a snapshot whose cloud copy failed to go up, kept until it
// does. RemoteKey is local bookkeeping: it separates save types even when
// their provider filenames are equal. See migrations/0039_cloud_retry_key.sql.
type CloudRetry struct {
	RemoteKey  string `db:"remote_key"`
	RemoteName string `db:"remote_name"`
	GameID     string `db:"game_id"`
	ZipPath    string `db:"zip_path"`
	FailedAtMs int64  `db:"failed_at_ms"`
	Attempts   int    `db:"attempts"`
}

// NoteCloudUploadFailed keeps a failed upload to try again, counting the
// attempts when it has failed before.
func (s *Store) NoteCloudUploadFailed(remoteKey, gameID, remoteName, zipPath string, atMs int64) error {
	_, err := s.db.Exec(`
		INSERT INTO cloud_upload_retry (remote_key, remote_name, game_id, zip_path, failed_at_ms)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT(remote_key) DO UPDATE SET
			remote_name = excluded.remote_name,
			zip_path = excluded.zip_path,
			failed_at_ms = excluded.failed_at_ms,
			attempts = attempts + 1`,
		remoteKey, remoteName, gameID, zipPath, atMs)
	if err != nil {
		return fmt.Errorf("note failed upload of %s: %w", remoteName, err)
	}
	return nil
}

// CloudRetries lists the failed uploads waiting to be sent again, oldest
// first.
func (s *Store) CloudRetries() ([]CloudRetry, error) {
	var out []CloudRetry
	if err := s.db.Select(&out, `
		SELECT remote_key, remote_name, game_id, zip_path, failed_at_ms, attempts
		FROM cloud_upload_retry ORDER BY failed_at_ms, remote_key`); err != nil {
		return nil, fmt.Errorf("list failed uploads: %w", err)
	}
	return out, nil
}

// ForgetCloudRetry drops a failed upload: sent now, or nothing left to send.
func (s *Store) ForgetCloudRetry(remoteKey string) error {
	if _, err := s.db.Exec(`DELETE FROM cloud_upload_retry WHERE remote_key = ?`, remoteKey); err != nil {
		return fmt.Errorf("forget failed upload of %s: %w", remoteKey, err)
	}
	return nil
}
