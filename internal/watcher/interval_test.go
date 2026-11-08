package watcher

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// The tests in this package drive several automatic snapshots in a row and
// expect each within seconds; the gap has its own test below.
func TestMain(m *testing.M) {
	minAutoSnapshotInterval = 0
	os.Exit(m.Run())
}

func TestAutoSnapshotDelayWaitsOutTheMinimumGap(t *testing.T) {
	defer func(d time.Duration) { minAutoSnapshotInterval = d }(minAutoSnapshotInterval)
	minAutoSnapshotInterval = 3 * time.Minute
	now := time.Date(2026, 11, 7, 12, 54, 0, 0, time.UTC)

	if got := autoSnapshotDelay(time.Time{}, now); got != debounceDelay {
		t.Fatalf("first change: delay %v, want the debounce %v", got, debounceDelay)
	}
	if got := autoSnapshotDelay(now.Add(-10*time.Second), now); got != 170*time.Second {
		t.Fatalf("10s after a snapshot: delay %v, want 2m50s", got)
	}
	if got := autoSnapshotDelay(now.Add(-179*time.Second), now); got != debounceDelay {
		t.Fatalf("1s before the gap ends: delay %v, want the debounce %v", got, debounceDelay)
	}
	if got := autoSnapshotDelay(now.Add(-10*time.Minute), now); got != debounceDelay {
		t.Fatalf("long after a snapshot: delay %v, want the debounce %v", got, debounceDelay)
	}
}

func TestAutoSnapshotIntervalFromEnv(t *testing.T) {
	t.Setenv("OPENSAVE_AUTO_SNAPSHOT_MIN_INTERVAL", "0s")
	if got := autoSnapshotIntervalFromEnv(time.Minute); got != 0 {
		t.Fatalf("got %v, want 0", got)
	}
	t.Setenv("OPENSAVE_AUTO_SNAPSHOT_MIN_INTERVAL", "nonsense")
	if got := autoSnapshotIntervalFromEnv(time.Minute); got != time.Minute {
		t.Fatalf("got %v, want the fallback", got)
	}
}

// A game rewriting its save every second gets one snapshot at once and one
// more when the gap ends, holding the last write; nothing in between.
func TestAutoSnapshotsWhilePlayingKeepTheGapAndTheLastWrite(t *testing.T) {
	defer func(d time.Duration) { minAutoSnapshotInterval = d }(minAutoSnapshotInterval)
	minAutoSnapshotInterval = 8 * time.Second

	saveDir := t.TempDir()
	col := newCollector()
	eng := New(col.callbacks())
	defer eng.Stop()
	if err := eng.Watch("game1", saveDir); err != nil {
		t.Fatalf("Watch() error = %v", err)
	}
	save := filepath.Join(saveDir, "slot1.sav")
	write := func(content string) {
		t.Helper()
		if err := os.WriteFile(save, []byte(content), 0o666); err != nil {
			t.Fatal(err)
		}
	}

	write("lap 0")
	if !waitFor(t, 10*time.Second, func() bool { return col.snapshotCount() >= 1 }) {
		t.Fatal("the first change should be snapshotted after the debounce")
	}
	start := time.Now()
	for i := 1; i <= 4; i++ {
		time.Sleep(time.Second)
		write("lap " + string(rune('0'+i)))
	}
	if n := col.snapshotCount(); n != 1 {
		t.Fatalf("%d snapshots during the gap, want 1", n)
	}
	if !waitFor(t, 15*time.Second, func() bool { return col.snapshotCount() >= 2 }) {
		t.Fatal("the writes made during the gap were never snapshotted")
	}
	if waited := time.Since(start); waited < 5*time.Second {
		t.Fatalf("second snapshot after %v, before the gap ended", waited)
	}
	time.Sleep(3 * time.Second)
	if n := col.snapshotCount(); n != 2 {
		t.Fatalf("%d snapshots, want 2: the last write is in the second one", n)
	}
}
