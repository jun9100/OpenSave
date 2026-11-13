package cliapp

import (
	"testing"
)

// A device that has never pushed needs every tracked game uploaded; typing the
// id once per game is what people forget, and a cloud that lost its copy has
// dozens of them.
func TestCloudPushAllPushesEveryTrackedGame(t *testing.T) {
	stub := startStubDaemon(t, map[string]answer{
		"GET /api/games":               {200, `{"b":{"name":"B"},"a":{"name":"A"}}`},
		"POST /api/cloud/sync-local/a": {200, `{"uploaded":2}`},
		"POST /api/cloud/sync-local/b": {200, `{"uploaded":0}`},
	})

	if code := Run([]string{"cloud", "push", "--all", "--json"}); code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if got := len(stub.requests("POST", "/api/cloud/sync-local/a")); got != 1 {
		t.Fatalf("game a pushed %d times, want 1", got)
	}
	if got := len(stub.requests("POST", "/api/cloud/sync-local/b")); got != 1 {
		t.Fatalf("game b pushed %d times, want 1", got)
	}
}

func TestCloudPushAllReportsFailures(t *testing.T) {
	stub := startStubDaemon(t, map[string]answer{
		"GET /api/games":               {200, `{"a":{}}`},
		"POST /api/cloud/sync-local/a": {500, `{"error":"cloud unreachable","failed":1}`},
	})

	if code := Run([]string{"cloud", "push", "--all", "--json"}); code == 0 {
		t.Fatal("a failed push must not exit 0")
	}
	if got := len(stub.requests("POST", "/api/cloud/sync-local/a")); got != 1 {
		t.Fatalf("game a pushed %d times, want 1", got)
	}
}

func TestCloudPushWithoutGameOrAllExplainsItself(t *testing.T) {
	stub := startStubDaemon(t, map[string]answer{})
	if code := Run([]string{"cloud", "push"}); code == 0 {
		t.Fatal("missing game id must not exit 0")
	}
	if len(stub.seen) != 0 {
		t.Fatalf("no daemon call expected, got %v", stub.seen)
	}
}
