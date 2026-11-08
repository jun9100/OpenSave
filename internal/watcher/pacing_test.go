package watcher

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// A game that keeps saving while it is played gets one automatic snapshot per
// MinInterval, and the change that arrived inside the interval is still taken
// when it ends, as the folder is then.
func TestMinIntervalPacesSnapshotsAndKeepsTheLastChange(t *testing.T) {
	saveDir := t.TempDir()
	save := filepath.Join(saveDir, "profile.sav")
	col := newCollector()
	cb := col.callbacks()
	const interval = 8 * time.Second
	cb.MinInterval = interval

	var mu sync.Mutex
	var taken []string // the save's content at each snapshot
	create := cb.CreateSnapshot
	cb.CreateSnapshot = func(gameID string) error {
		data, err := os.ReadFile(save)
		if err != nil {
			return err
		}
		mu.Lock()
		taken = append(taken, string(data))
		mu.Unlock()
		return create(gameID)
	}
	contents := func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), taken...)
	}

	eng := New(cb)
	defer eng.Stop()
	if err := eng.Watch("game1", saveDir); err != nil {
		t.Fatal(err)
	}

	write := func(s string) {
		t.Helper()
		if err := os.WriteFile(save, []byte(s), 0o666); err != nil {
			t.Fatal(err)
		}
	}

	// The first change after a quiet start is snapshotted right away.
	write("lap 1")
	if !waitFor(t, 10*time.Second, func() bool { return col.snapshotCount() >= 1 }) {
		t.Fatal("expected the first change to be snapshotted after the debounce")
	}
	first := time.Now()

	// Two more saves, each after its own quiet period, inside the interval.
	write("lap 2")
	time.Sleep(debounceDelay + time.Second)
	write("lap 3")
	time.Sleep(debounceDelay + time.Second)
	if elapsed := time.Since(first); elapsed < interval-time.Second {
		if got := col.snapshotCount(); got != 1 {
			t.Fatalf("snapshots inside the interval = %d after %s, want 1", got, elapsed.Round(time.Second))
		}
	}

	// When the interval ends, one snapshot of the folder as it is then.
	if !waitFor(t, interval+5*time.Second, func() bool { return col.snapshotCount() >= 2 }) {
		t.Fatal("the change held back by the interval was never snapshotted")
	}
	time.Sleep(debounceDelay + time.Second)
	got := contents()
	if len(got) != 2 || got[0] != "lap 1" || got[1] != "lap 3" {
		t.Fatalf("snapshots took %q, want [lap 1 lap 3]", got)
	}
}

// Zero keeps the old behaviour: every quiet period after a change snapshots.
func TestZeroMinIntervalSnapshotsEveryQuietPeriod(t *testing.T) {
	saveDir := t.TempDir()
	col := newCollector()
	eng := New(col.callbacks())
	defer eng.Stop()
	if err := eng.Watch("game1", saveDir); err != nil {
		t.Fatal(err)
	}
	for i, s := range []string{"a", "b"} {
		if err := os.WriteFile(filepath.Join(saveDir, "slot.sav"), []byte(s), 0o666); err != nil {
			t.Fatal(err)
		}
		if !waitFor(t, 10*time.Second, func() bool { return col.snapshotCount() >= i+1 }) {
			t.Fatalf("change %d was not snapshotted", i+1)
		}
	}
}
