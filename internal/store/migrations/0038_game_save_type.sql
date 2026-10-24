-- Save origin is deliberately a game property: the same title can have a
-- native PC save and an emulator save and they must never share cloud data.
ALTER TABLE games ADD COLUMN save_type TEXT NOT NULL DEFAULT 'pc';
