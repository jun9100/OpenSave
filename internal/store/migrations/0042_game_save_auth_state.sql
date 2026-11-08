-- Device-token authentication state for the game-save provider. A non-empty
-- block records why the service stopped accepting this device (token_revoked
-- or shared_token_disabled), so automatic sync can stop without retrying.
-- The warning flag is 0 (none), 1 (show the shared-token deprecation notice
-- once), or 2 (the user dismissed it). Both are local state, not credentials.
ALTER TABLE cloud_config ADD COLUMN game_save_auth_block TEXT NOT NULL DEFAULT '';
ALTER TABLE cloud_config ADD COLUMN game_save_auth_warning INTEGER NOT NULL DEFAULT 0;
