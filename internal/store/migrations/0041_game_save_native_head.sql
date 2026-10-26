-- The native game-save CAS protocol assigns a server-global snapshot id to a
-- device-local snapshot. Keep that confirmation separate from the local id
-- and from the legacy head-file announcement marker, so other cloud providers
-- retain their existing behavior.
ALTER TABLE cloud_heads ADD COLUMN native_head TEXT NOT NULL DEFAULT '';
