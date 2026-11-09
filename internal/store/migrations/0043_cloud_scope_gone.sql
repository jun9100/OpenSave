-- A game-save scope that the server has tombstoned as deleted or merged.
-- This is deliberately separate from untracked_games: an untrack tombstone
-- says the user stopped following a game on this device, while this records
-- a server-side scope refusal. It keeps the cloud sync stopped and the local
-- confirmed head cleared until the user reopens the scope.
CREATE TABLE cloud_scope_gone (
    game_id         TEXT PRIMARY KEY,
    code            TEXT NOT NULL,
    gone_at         TEXT NOT NULL,
    successor_type  TEXT NOT NULL DEFAULT '',
    successor_game_id TEXT NOT NULL DEFAULT '',
    successor_name  TEXT NOT NULL DEFAULT '',
    notice          TEXT NOT NULL DEFAULT 'pending',
    resume_refused  INTEGER NOT NULL DEFAULT 0,
    recorded_at     TEXT NOT NULL
);
