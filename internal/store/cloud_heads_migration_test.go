package store

import (
	"database/sql"
	"testing"
)

func TestCloudHeadContentTimeMigrationPreservesExistingHeads(t *testing.T) {
	db, err := sql.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	if _, err := db.Exec(`
		CREATE TABLE cloud_heads (
			game_id   TEXT PRIMARY KEY,
			snapshot  TEXT NOT NULL DEFAULT '',
			since     TEXT NOT NULL DEFAULT '',
			file      TEXT NOT NULL DEFAULT '',
			chain     TEXT NOT NULL DEFAULT '[]',
			published TEXT NOT NULL DEFAULT '',
			dismissed TEXT NOT NULL DEFAULT ''
		);
		INSERT INTO cloud_heads (game_id, snapshot, since, file, chain, published, dismissed)
		VALUES ('odyssey', 'snap_1730000000000', '2026-10-26T00:00:00Z',
			'odyssey__main__snap_1730000000000.zip', '["snap_1730000000000"]',
			'snap_1730000000000', '');
	`); err != nil {
		t.Fatalf("create pre-0040 cloud head: %v", err)
	}

	migration, err := migrationsFS.ReadFile("migrations/0040_cloud_head_content_at.sql")
	if err != nil {
		t.Fatalf("read 0040 migration: %v", err)
	}
	if _, err := db.Exec(string(migration)); err != nil {
		t.Fatalf("apply 0040 migration: %v", err)
	}

	var snapshot string
	var contentAt int64
	if err := db.QueryRow(`SELECT snapshot, content_at FROM cloud_heads WHERE game_id = 'odyssey'`).Scan(&snapshot, &contentAt); err != nil {
		t.Fatal(err)
	}
	if snapshot != "snap_1730000000000" {
		t.Errorf("snapshot = %q, want existing cloud head", snapshot)
	}
	if contentAt != 0 {
		t.Errorf("content_at = %d, want 0 for an unverifiable pre-0040 head", contentAt)
	}
}

func TestGameSaveNativeHeadMigrationPreservesExistingHeads(t *testing.T) {
	db, err := sql.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`
		CREATE TABLE cloud_heads (
			game_id TEXT PRIMARY KEY,
			snapshot TEXT NOT NULL DEFAULT '',
			since TEXT NOT NULL DEFAULT '',
			file TEXT NOT NULL DEFAULT '',
			chain TEXT NOT NULL DEFAULT '[]',
			content_at INTEGER NOT NULL DEFAULT 0,
			published TEXT NOT NULL DEFAULT '',
			dismissed TEXT NOT NULL DEFAULT ''
		);
		INSERT INTO cloud_heads (game_id, snapshot, since, file, chain, content_at, published, dismissed)
		VALUES ('odyssey', 'snap_1730000000000', '2026-10-26T00:00:00Z',
			'odyssey__main__snap_1730000000000.zip', '["snap_1730000000000"]', 1730000000000,
			'snap_1730000000000', '');
	`); err != nil {
		t.Fatal(err)
	}

	migration, err := migrationsFS.ReadFile("migrations/0041_game_save_native_head.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(string(migration)); err != nil {
		t.Fatal(err)
	}
	var snapshot, nativeHead string
	if err := db.QueryRow(`SELECT snapshot, native_head FROM cloud_heads WHERE game_id = 'odyssey'`).Scan(&snapshot, &nativeHead); err != nil {
		t.Fatal(err)
	}
	if snapshot != "snap_1730000000000" || nativeHead != "" {
		t.Fatalf("migrated cloud head = (%q, %q)", snapshot, nativeHead)
	}
}
