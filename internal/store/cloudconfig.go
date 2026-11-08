package store

import (
	"encoding/json"
	"fmt"
)

// CloudConfig is the singleton cloud-backup configuration row, including
// the current provider's OAuth tokens. It mirrors the JS app's
// settings.cloudSync object, flattened (tokens.* -> top-level columns).
type CloudConfig struct {
	ID                  int               `db:"id" json:"-"`
	Enabled             bool              `db:"enabled" json:"enabled"`
	Provider            string            `db:"provider" json:"provider"`
	URL                 string            `db:"url" json:"url"`
	Username            string            `db:"username" json:"username"`
	Password            string            `db:"password" json:"password"`
	HeadersJSON         string            `db:"headers_json" json:"headers"`
	FolderID            string            `db:"folder_id" json:"folderId"`
	CustomClientIDs     map[string]string `db:"-" json:"customClientIds"`
	CustomClientSecrets map[string]string `db:"-" json:"customClientSecrets"`
	AccessToken         string            `db:"access_token" json:"-"`
	RefreshToken        string            `db:"refresh_token" json:"-"`
	ExpiryTimeMs        int64             `db:"expiry_time_ms" json:"-"`
	UserEmail           string            `db:"user_email" json:"-"`
	// GameSaveAuthBlock records a service refusal (token_revoked or
	// shared_token_disabled) so automatic sync stops until the user pairs
	// again. GameSaveAuthWarning is 0 (none), 1 (pending notice), or 2
	// (dismissed). Neither is a credential, and neither is written by
	// UpdateCloudConfig: only their dedicated methods may change them.
	GameSaveAuthBlock   string `db:"game_save_auth_block" json:"-"`
	GameSaveAuthWarning int    `db:"game_save_auth_warning" json:"-"`

	CustomClientIDsJSON     string `db:"custom_client_ids" json:"-"`
	CustomClientSecretsJSON string `db:"custom_client_secrets" json:"-"`
}

// Shared-token deprecation notice states stored in CloudConfig.GameSaveAuthWarning.
const (
	GameSaveAuthWarningNone      = 0
	GameSaveAuthWarningPending   = 1
	GameSaveAuthWarningDismissed = 2
)

// GetCloudConfig returns the singleton cloud config row.
func (s *Store) GetCloudConfig() (CloudConfig, error) {
	var c CloudConfig
	if err := s.db.Get(&c, `SELECT * FROM cloud_config WHERE id = 1`); err != nil {
		return CloudConfig{}, fmt.Errorf("get cloud config: %w", err)
	}
	if c.CustomClientIDsJSON != "" {
		if err := json.Unmarshal([]byte(c.CustomClientIDsJSON), &c.CustomClientIDs); err != nil {
			return CloudConfig{}, fmt.Errorf("unmarshal customClientIds: %w", err)
		}
	}
	if c.CustomClientSecretsJSON != "" {
		if err := json.Unmarshal([]byte(c.CustomClientSecretsJSON), &c.CustomClientSecrets); err != nil {
			return CloudConfig{}, fmt.Errorf("unmarshal customClientSecrets: %w", err)
		}
	}
	return c, nil
}

// UpdateCloudConfig persists the given config as the new singleton row.
func (s *Store) UpdateCloudConfig(c CloudConfig) error {
	if c.CustomClientIDs == nil {
		c.CustomClientIDs = map[string]string{}
	}
	if c.CustomClientSecrets == nil {
		c.CustomClientSecrets = map[string]string{}
	}
	idsJSON, err := json.Marshal(c.CustomClientIDs)
	if err != nil {
		return err
	}
	secretsJSON, err := json.Marshal(c.CustomClientSecrets)
	if err != nil {
		return err
	}
	c.CustomClientIDsJSON = string(idsJSON)
	c.CustomClientSecretsJSON = string(secretsJSON)

	_, err = s.db.NamedExec(`
		UPDATE cloud_config SET
			enabled = :enabled,
			provider = :provider,
			url = :url,
			username = :username,
			password = :password,
			headers_json = :headers_json,
			folder_id = :folder_id,
			custom_client_ids = :custom_client_ids,
			custom_client_secrets = :custom_client_secrets,
			access_token = :access_token,
			refresh_token = :refresh_token,
			expiry_time_ms = :expiry_time_ms,
			user_email = :user_email
		WHERE id = 1`, c)
	if err != nil {
		return fmt.Errorf("update cloud config: %w", err)
	}
	return nil
}

// SetGameSaveAuthBlock records why game-save stopped accepting this device.
// An empty code clears the block, which pairing and credential changes do.
func (s *Store) SetGameSaveAuthBlock(code string) error {
	if _, err := s.db.Exec(`UPDATE cloud_config SET game_save_auth_block = ? WHERE id = 1`, code); err != nil {
		return fmt.Errorf("set game-save auth block: %w", err)
	}
	return nil
}

// SetGameSaveAuthWarning stores the shared-token deprecation notice state.
func (s *Store) SetGameSaveAuthWarning(state int) error {
	if _, err := s.db.Exec(`UPDATE cloud_config SET game_save_auth_warning = ? WHERE id = 1`, state); err != nil {
		return fmt.Errorf("set game-save auth warning: %w", err)
	}
	return nil
}

// MarkGameSaveAuthWarningSeen turns the unseen state into pending. It reports
// false when the notice was already pending or dismissed, so callers can log
// only the first sighting rather than once per response.
func (s *Store) MarkGameSaveAuthWarningSeen() (changed bool, err error) {
	res, err := s.db.Exec(`UPDATE cloud_config SET game_save_auth_warning = ? WHERE id = 1 AND game_save_auth_warning = ?`,
		GameSaveAuthWarningPending, GameSaveAuthWarningNone)
	if err != nil {
		return false, fmt.Errorf("mark game-save auth warning seen: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, fmt.Errorf("mark game-save auth warning seen: %w", err)
	}
	return n > 0, nil
}
