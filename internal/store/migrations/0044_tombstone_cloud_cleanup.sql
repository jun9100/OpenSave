-- What "delete its cloud copies" needs after a game is untracked. The game
-- row is gone by then, so the cleanup offered right after an untrack had no
-- save type to scope the provider listing by and always answered 404.
--
-- save_type is NULL when unknown: tombstones written before this migration,
-- and ones whose game row was already missing. An empty string is a known
-- value (the pre-save-type PC default), so the two must stay distinct.
--
-- cloud_scope_gone carries over a game-save scope tombstone the untrack
-- removed from cloud_scope_gone, so the cleanup still refuses a deleted or
-- merged scope. Re-tracking deletes the whole row, so a new game with the
-- same id inherits neither.
ALTER TABLE untracked_games ADD COLUMN save_type TEXT;
ALTER TABLE untracked_games ADD COLUMN cloud_scope_gone INTEGER NOT NULL DEFAULT 0;
