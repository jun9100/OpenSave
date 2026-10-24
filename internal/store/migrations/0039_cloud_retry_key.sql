-- A native game-save namespace is save-type scoped, so a PC and an emulator
-- upload may legitimately use the same provider filename. The older retry
-- table keyed only by that filename and silently replaced one waiting upload
-- with the other. Keep the provider name separately and key local retries by
-- a stable type/name value instead.
ALTER TABLE cloud_upload_retry RENAME TO cloud_upload_retry_0036;

CREATE TABLE cloud_upload_retry (
    remote_key   TEXT PRIMARY KEY,
    remote_name  TEXT NOT NULL,
    game_id      TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    zip_path     TEXT NOT NULL,
    failed_at_ms INTEGER NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 1
);

-- Pre-type installations were all PC backups. char(0) matches cloud.RetryKey
-- without assuming old filenames can be parsed or that their game still exists.
INSERT INTO cloud_upload_retry (remote_key, remote_name, game_id, zip_path, failed_at_ms, attempts)
SELECT 'pc' || char(0) || remote_name, remote_name, game_id, zip_path, failed_at_ms, attempts
FROM cloud_upload_retry_0036;

DROP TABLE cloud_upload_retry_0036;
