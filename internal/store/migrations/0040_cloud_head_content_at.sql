-- Snapshot IDs name the time a backup was made, which can be much later than
-- the save content inside it. Keep that content time separately so cloud
-- conflict resolution does not promote an old save merely because it was
-- copied or uploaded later.
ALTER TABLE cloud_heads ADD COLUMN content_at INTEGER NOT NULL DEFAULT 0;
