package store

import (
	"database/sql"
	"testing"
)

func TestGameSaveAuthStateDefaultsAndDedicatedWrites(t *testing.T) {
	s := openTestStore(t)
	if err := s.EnsureDefaultSettings(t.TempDir(), t.TempDir()); err != nil {
		t.Fatal(err)
	}

	cfg, err := s.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.GameSaveAuthBlock != "" || cfg.GameSaveAuthWarning != GameSaveAuthWarningNone {
		t.Fatalf("default auth state = (%q, %d), want empty/0", cfg.GameSaveAuthBlock, cfg.GameSaveAuthWarning)
	}

	cfg.Password = "a-key"
	if err := s.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}
	cfg, _ = s.GetCloudConfig()
	if cfg.GameSaveAuthBlock != "" || cfg.GameSaveAuthWarning != GameSaveAuthWarningNone {
		t.Fatalf("UpdateCloudConfig invented auth state: (%q, %d)", cfg.GameSaveAuthBlock, cfg.GameSaveAuthWarning)
	}

	if err := s.SetGameSaveAuthBlock("token_revoked"); err != nil {
		t.Fatal(err)
	}
	if err := s.SetGameSaveAuthWarning(GameSaveAuthWarningPending); err != nil {
		t.Fatal(err)
	}
	changed, err := s.MarkGameSaveAuthWarningSeen()
	if err != nil || changed {
		t.Fatalf("MarkGameSaveAuthWarningSeen with pending = %v, %v; want false, nil", changed, err)
	}

	cfg, _ = s.GetCloudConfig()
	cfg.URL = "https://nas.example"
	if err := s.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}
	cfg, _ = s.GetCloudConfig()
	if cfg.GameSaveAuthBlock != "token_revoked" || cfg.GameSaveAuthWarning != GameSaveAuthWarningPending {
		t.Fatalf("UpdateCloudConfig overwrote auth state: (%q, %d)", cfg.GameSaveAuthBlock, cfg.GameSaveAuthWarning)
	}

	if err := s.SetGameSaveAuthWarning(GameSaveAuthWarningNone); err != nil {
		t.Fatal(err)
	}
	changed, err = s.MarkGameSaveAuthWarningSeen()
	if err != nil || !changed {
		t.Fatalf("MarkGameSaveAuthWarningSeen from none = %v, %v; want true, nil", changed, err)
	}
	cfg, _ = s.GetCloudConfig()
	if cfg.GameSaveAuthWarning != GameSaveAuthWarningPending {
		t.Fatalf("warning = %d, want pending", cfg.GameSaveAuthWarning)
	}
	if changed, err = s.MarkGameSaveAuthWarningSeen(); err != nil || changed {
		t.Fatalf("second MarkGameSaveAuthWarningSeen = %v, %v; want false, nil", changed, err)
	}

	if err := s.SetGameSaveAuthWarning(GameSaveAuthWarningDismissed); err != nil {
		t.Fatal(err)
	}
	if changed, err = s.MarkGameSaveAuthWarningSeen(); err != nil || changed {
		t.Fatalf("MarkGameSaveAuthWarningSeen after dismissal = %v, %v; want false, nil", changed, err)
	}
	cfg, _ = s.GetCloudConfig()
	if cfg.GameSaveAuthWarning != GameSaveAuthWarningDismissed {
		t.Fatalf("dismissed warning changed to %d", cfg.GameSaveAuthWarning)
	}
}

func TestGameSaveAuthStateMigrationDefaults(t *testing.T) {
	db, err := sql.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`
		CREATE TABLE cloud_config (
			id       INTEGER PRIMARY KEY CHECK (id = 1),
			enabled  INTEGER NOT NULL DEFAULT 1,
			provider TEXT NOT NULL DEFAULT 'local',
			url      TEXT NOT NULL DEFAULT '',
			password TEXT NOT NULL DEFAULT ''
		);
		INSERT INTO cloud_config (id, password) VALUES (1, 'existing-key');
	`); err != nil {
		t.Fatalf("create pre-0042 cloud config: %v", err)
	}

	migration, err := migrationsFS.ReadFile("migrations/0042_game_save_auth_state.sql")
	if err != nil {
		t.Fatalf("read 0042 migration: %v", err)
	}
	if _, err := db.Exec(string(migration)); err != nil {
		t.Fatalf("apply 0042 migration: %v", err)
	}

	var password, block string
	var warning int
	if err := db.QueryRow(`SELECT password, game_save_auth_block, game_save_auth_warning FROM cloud_config WHERE id = 1`).
		Scan(&password, &block, &warning); err != nil {
		t.Fatal(err)
	}
	if password != "existing-key" {
		t.Fatalf("existing password = %q", password)
	}
	if block != "" || warning != GameSaveAuthWarningNone {
		t.Fatalf("new columns = (%q, %d), want empty/0", block, warning)
	}
}
