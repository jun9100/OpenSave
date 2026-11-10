package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"sync"
	"testing"

	"github.com/opensave/opensave/internal/store"
)

// untrackCleanupService is a game-save stand-in holding a fixed listing. It
// records every DELETE (as "type/name") and every scope GET path, and answers
// scope GETs with scopeStatus (200 with an empty scope, 404 for an older
// service, or a 410 tombstone body when scopeGone is set).
type untrackCleanupService struct {
	mu        sync.Mutex
	deletes   []string
	scopeGets []string
	other     int
}

func startUntrackCleanupService(t *testing.T, scopeGone string) (*httptest.Server, *untrackCleanupService) {
	t.Helper()
	rec := &untrackCleanupService{}
	files := []map[string]any{
		// The untracked game's own switch copies: these are what cleanup is for.
		{"type": "switch", "name": "hollow-knight__main__s1.zip", "sizeBytes": 10, "createdTime": "2026-11-01T00:00:00Z"},
		{"type": "switch", "name": "hollow-knight__main__s2.zip", "sizeBytes": 10, "createdTime": "2026-11-02T00:00:00Z"},
		// Same id under another save type: a different game in the typed store.
		{"type": "pc", "name": "hollow-knight__main__p1.zip", "sizeBytes": 10, "createdTime": "2026-11-01T00:00:00Z"},
		// Another game, and a linked id another device uses for this title.
		{"type": "switch", "name": "silksong__main__x1.zip", "sizeBytes": 10, "createdTime": "2026-11-01T00:00:00Z"},
		{"type": "switch", "name": "hollow-knight-pc__main__a1.zip", "sizeBytes": 10, "createdTime": "2026-11-01T00:00:00Z"},
	}
	service := goneTestService(t, func(w http.ResponseWriter, r *http.Request) {
		rec.mu.Lock()
		defer rec.mu.Unlock()
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/v1/opensave/files":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"files": files})
		case r.Method == http.MethodDelete && strings.HasPrefix(r.URL.Path, "/v1/opensave/files/"):
			rec.deletes = append(rec.deletes, strings.TrimPrefix(r.URL.Path, "/v1/opensave/files/"))
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/v1/opensave/scopes/"):
			rec.scopeGets = append(rec.scopeGets, r.URL.Path)
			if scopeGone != "" {
				writeGoneResponse(w, scopeGone)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"type": "switch", "gameId": "hollow-knight", "snapshots": []any{}, "conflicts": []any{},
			})
		default:
			rec.other++
			w.WriteHeader(http.StatusNotFound)
		}
	})
	return service, rec
}

func (s *untrackCleanupService) snapshot() (deletes, scopeGets []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	deletes = append([]string(nil), s.deletes...)
	scopeGets = append([]string(nil), s.scopeGets...)
	sort.Strings(deletes)
	return deletes, scopeGets
}

func untrackForCleanupTest(t *testing.T, ts *testServer, gameID string) {
	t.Helper()
	resp, body := ts.do(t, http.MethodDelete, "/api/games/"+gameID, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("untrack status = %d, body = %v", resp.StatusCode, body)
	}
	if _, err := ts.daemon.Store.GetGame(gameID); err == nil {
		t.Fatal("game still tracked after untrack")
	}
}

// The cleanup offered after an untrack runs once the game row is gone. It
// must still find the save type and remove exactly this game's copies.
func TestCloudDeleteGameAfterUntrack(t *testing.T) {
	ts := startTestServer(t)
	service, rec := startUntrackCleanupService(t, "")
	configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
	untrackForCleanupTest(t, ts, "hollow-knight")

	resp, body := ts.do(t, http.MethodPost, "/api/cloud/delete-game/hollow-knight", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("delete status = %d, body = %v", resp.StatusCode, body)
	}
	if got := string(body["deleted"]); got != "2" {
		t.Fatalf("deleted = %s, want 2 (body %v)", got, body)
	}
	if got := string(body["failed"]); got != "0" {
		t.Fatalf("failed = %s, want 0", got)
	}
	deletes, scopeGets := rec.snapshot()
	want := []string{"switch/hollow-knight__main__s1.zip", "switch/hollow-knight__main__s2.zip"}
	if strings.Join(deletes, ",") != strings.Join(want, ",") {
		t.Fatalf("deleted %v, want only %v", deletes, want)
	}
	if len(scopeGets) != 1 || scopeGets[0] != "/v1/opensave/scopes/switch/hollow-knight" {
		t.Fatalf("scope GETs = %v, want the switch scope once", scopeGets)
	}
}

// A scope this device knew was merged must stay refused after the untrack
// dropped its cloud_scope_gone row — even when the server would now answer
// the scope GET normally.
func TestCloudDeleteGameAfterUntrackRefusesKnownMergedScope(t *testing.T) {
	ts := startTestServer(t)
	service, rec := startUntrackCleanupService(t, "")
	configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
	putGoneAPITest(t, ts, "hollow-knight", store.CloudScopeGone{
		Code: "scope_retired", SuccessorType: "switch", SuccessorGameID: "hollow-knight-main",
	})
	untrackForCleanupTest(t, ts, "hollow-knight")

	resp, body := ts.do(t, http.MethodPost, "/api/cloud/delete-game/hollow-knight", nil)
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("delete status = %d, body = %v", resp.StatusCode, body)
	}
	if got := string(body["error"]); got != `"this game's cloud save was deleted or merged on the server; its cloud copies are left as they are"` {
		t.Fatalf("delete error = %s", got)
	}
	deletes, scopeGets := rec.snapshot()
	if len(deletes) != 0 || len(scopeGets) != 0 {
		t.Fatalf("refused cleanup still reached the service: deletes=%v scopeGets=%v", deletes, scopeGets)
	}
}

// A merge this device had not heard of yet is caught by the scope GET.
func TestCloudDeleteGameAfterUntrackRefusesServerMergedScope(t *testing.T) {
	for _, code := range []string{"scope_retired", "scope_deleted"} {
		t.Run(code, func(t *testing.T) {
			ts := startTestServer(t)
			service, rec := startUntrackCleanupService(t, code)
			configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
			untrackForCleanupTest(t, ts, "hollow-knight")

			resp, body := ts.do(t, http.MethodPost, "/api/cloud/delete-game/hollow-knight", nil)
			if resp.StatusCode != http.StatusConflict {
				t.Fatalf("delete status = %d, body = %v", resp.StatusCode, body)
			}
			deletes, scopeGets := rec.snapshot()
			if len(deletes) != 0 {
				t.Fatalf("deleted %v, want none", deletes)
			}
			if len(scopeGets) != 1 || scopeGets[0] != "/v1/opensave/scopes/switch/hollow-knight" {
				t.Fatalf("scope GETs = %v, want the switch scope once", scopeGets)
			}
		})
	}
}

// A tombstone written before save types were kept says nothing about which
// typed namespace the copies are in; guessing pc could remove another
// game's. Refuse instead.
func TestCloudDeleteGameUntrackedWithoutSaveTypeRefused(t *testing.T) {
	ts := startTestServer(t)
	service, rec := startUntrackCleanupService(t, "")
	configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
	if err := ts.daemon.Store.DeleteGame("hollow-knight"); err != nil {
		t.Fatal(err)
	}
	if err := ts.daemon.Store.AddUntrackedTombstone("hollow-knight", "Hollow Knight", ""); err != nil {
		t.Fatal(err)
	}

	resp, body := ts.do(t, http.MethodPost, "/api/cloud/delete-game/hollow-knight", nil)
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("delete status = %d, body = %v", resp.StatusCode, body)
	}
	deletes, scopeGets := rec.snapshot()
	if len(deletes) != 0 || len(scopeGets) != 0 {
		t.Fatalf("unknown save type still reached the service: deletes=%v scopeGets=%v", deletes, scopeGets)
	}
}

// Re-tracking clears the tombstone, so a merged-scope refusal recorded at the
// untrack does not follow the id into a new game, and an id that was never
// tracked here is still a 404.
func TestCloudDeleteGameRetrackDropsCarriedRefusal(t *testing.T) {
	ts := startTestServer(t)
	service, _ := startUntrackCleanupService(t, "")
	configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
	putGoneAPITest(t, ts, "hollow-knight", store.CloudScopeGone{Code: "scope_deleted"})
	untrackForCleanupTest(t, ts, "hollow-knight")

	if state, ok, err := ts.daemon.Store.GetUntrackedCloudState("hollow-knight"); err != nil || !ok ||
		!state.SaveTypeKnown || state.SaveType != "switch" || !state.ScopeGone {
		t.Fatalf("tombstone cloud state = %+v ok=%v err=%v", state, ok, err)
	}
	if err := ts.daemon.Store.ClearUntrackedTombstone("hollow-knight"); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := ts.daemon.Store.GetUntrackedCloudState("hollow-knight"); err != nil || ok {
		t.Fatalf("cloud state survived re-track: ok=%v err=%v", ok, err)
	}

	resp, body := ts.do(t, http.MethodPost, "/api/cloud/delete-game/never-tracked", nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown id status = %d, body = %v", resp.StatusCode, body)
	}
}
