package daemon

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/opensave/opensave/internal/store"
)

// Tracking a folder inside, or around, another game's save location is
// refused unless asked for: both games would snapshot and upload every save.
func TestTrackGameRefusesOverlappingFoldersUnlessAllowed(t *testing.T) {
	d := newTestDaemon(t)
	root := t.TempDir()
	outer := filepath.Join(root, "CODEX")
	inner := filepath.Join(outer, "1551360", "remote")
	sibling := filepath.Join(root, "Other")
	for _, dir := range []string{inner, sibling} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}

	if _, err := d.TrackGame(store.Game{Name: "Forza Horizon 5", SavePath: outer}); err != nil {
		t.Fatalf("first track: %v", err)
	}

	_, err := d.TrackGame(store.Game{Name: "Forza Horizon 5", SavePath: inner})
	var overlap *OverlapError
	if !errors.As(err, &overlap) {
		t.Fatalf("tracking a folder inside a tracked one: err = %v, want *OverlapError", err)
	}
	if overlap.Name != "Forza Horizon 5" {
		t.Errorf("overlap names %q, want the tracked game", overlap.Name)
	}

	// A folder that holds a tracked one is the same overlap the other way.
	d2 := newTestDaemon(t)
	if _, err := d2.TrackGame(store.Game{Name: "Inner", SavePath: inner}); err != nil {
		t.Fatal(err)
	}
	if _, err := d2.TrackGame(store.Game{Name: "Outer", SavePath: outer}); !errors.As(err, &overlap) {
		t.Fatalf("tracking a folder around a tracked one: err = %v, want *OverlapError", err)
	}

	// Unrelated folders are unaffected, and the user can insist.
	if _, err := d.TrackGame(store.Game{Name: "Other", SavePath: sibling}); err != nil {
		t.Fatalf("unrelated folder: %v", err)
	}
	g, err := d.TrackGameOverlapping(store.Game{Name: "Forza Horizon 5", SavePath: inner})
	if err != nil {
		t.Fatalf("overlap allowed by the user: %v", err)
	}
	if g.ID != "forza-horizon-5-2" {
		t.Errorf("id = %q, want forza-horizon-5-2", g.ID)
	}
}
