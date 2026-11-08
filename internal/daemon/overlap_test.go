package daemon

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/opensave/opensave/internal/store"
)

// Forza Horizon 5 tracked at CODEX and again at CODEX/1551360/remote became
// forza-horizon-5 and forza-horizon-5-2, both snapshotting every save.
// Adding either folder after the other is refused, in both orders, while a
// folder that only shares a name prefix is not an overlap.
func TestTrackGameRefusesAFolderOverlappingATrackedOne(t *testing.T) {
	root := t.TempDir()
	outer := filepath.Join(root, "CODEX")
	inner := filepath.Join(outer, "1551360", "remote")
	sibling := filepath.Join(root, "CODEX-old")
	for _, dir := range []string{inner, sibling} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "user.profile"), []byte("x"), 0o666); err != nil {
			t.Fatal(err)
		}
	}

	for _, order := range [][2]string{{outer, inner}, {inner, outer}} {
		d := newTestDaemon(t)
		first, err := d.TrackGame(store.Game{Name: "Forza Horizon 5", SavePath: order[0]})
		if err != nil {
			t.Fatal(err)
		}
		_, err = d.TrackGame(store.Game{Name: "Forza Horizon 5", SavePath: order[1]})
		if !errors.Is(err, ErrOverlapsTrackedGame) {
			t.Fatalf("tracking %s after %s: err = %v, want ErrOverlapsTrackedGame", order[1], order[0], err)
		}
		if !strings.Contains(err.Error(), "tracked folder of") {
			t.Fatalf("error %q does not name the tracked game", err)
		}
		games, _ := d.Store.ListGames()
		if len(games) != 1 || games[0].ID != first.ID {
			t.Fatalf("games after the refusal: %+v, want only %s", games, first.ID)
		}
		if _, err := d.TrackGame(store.Game{Name: "Forza Horizon 5 old", SavePath: sibling}); err != nil {
			t.Fatalf("a folder that only shares a name prefix was refused: %v", err)
		}
		d.WaitForTracking()
	}
}
