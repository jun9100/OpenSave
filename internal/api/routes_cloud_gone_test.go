package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

func configureGoneAPITest(t *testing.T, ts *testServer, serviceURL string, gameID, gameName string) {
	t.Helper()
	if err := ts.daemon.Store.CreateGame(store.Game{
		ID: gameID, Name: gameName, SavePath: ts.saveDir, ActiveBranch: "main",
		AutoSync: true, MaxSnapshots: 20, SaveType: "switch",
	}); err != nil {
		t.Fatal(err)
	}
	cfg, err := ts.daemon.Store.GetCloudConfig()
	if err != nil {
		t.Fatal(err)
	}
	cfg.Enabled, cfg.Provider, cfg.URL, cfg.Password = true, cloud.GameSaveProvider, serviceURL, "test-key"
	if err := ts.daemon.Store.UpdateCloudConfig(cfg); err != nil {
		t.Fatal(err)
	}
}

func putGoneAPITest(t *testing.T, ts *testServer, gameID string, gone store.CloudScopeGone) {
	t.Helper()
	gone.GameID = gameID
	if gone.GoneAt == "" {
		gone.GoneAt = time.Now().UTC().Format(time.RFC3339)
	}
	if gone.Notice == "" {
		gone.Notice = "pending"
	}
	if gone.RecordedAt == "" {
		gone.RecordedAt = time.Now().UTC().Format(time.RFC3339)
	}
	if err := ts.daemon.Store.PutCloudScopeGone(gone); err != nil {
		t.Fatal(err)
	}
}

func goneTestService(t *testing.T, handler func(http.ResponseWriter, *http.Request)) *httptest.Server {
	t.Helper()
	service := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		handler(w, r)
	}))
	t.Cleanup(service.Close)
	return service
}

func writeGoneResponse(w http.ResponseWriter, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusGone)
	body := map[string]any{"code": code}
	if code == "scope_deleted" {
		body["deletedAt"] = "2026-11-09T12:00:00Z"
	}
	if code == "scope_retired" {
		body["successor"] = map[string]string{"type": "switch", "gameId": "forza-horizon-5"}
	}
	_ = json.NewEncoder(w).Encode(body)
}

func TestCloudScopeGoneListAndOff(t *testing.T) {
	ts := startTestServer(t)
	service := goneTestService(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})
	configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
	putGoneAPITest(t, ts, "hollow-knight", store.CloudScopeGone{
		Code: "scope_deleted", SuccessorName: "",
	})

	resp, body := ts.do(t, http.MethodGet, "/api/cloud/scope-gone", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("list status = %d", resp.StatusCode)
	}
	var items []struct {
		GameID        string `json:"gameId"`
		GameName      string `json:"gameName"`
		Code          string `json:"code"`
		At            string `json:"at"`
		SuccessorType string `json:"successorType"`
		Notice        string `json:"notice"`
		ResumeRefused bool   `json:"resumeRefused"`
	}
	if err := json.Unmarshal(body["items"], &items); err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].GameID != "hollow-knight" || items[0].GameName != "Hollow Knight" ||
		items[0].Code != "scope_deleted" || items[0].At == "" || items[0].Notice != "pending" || items[0].ResumeRefused {
		t.Fatalf("items = %+v", items)
	}

	resp, body = ts.do(t, http.MethodPost, "/api/cloud/scope-gone/missing/off", nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("off missing status = %d, want 404", resp.StatusCode)
	}
	resp, _ = ts.do(t, http.MethodPost, "/api/cloud/scope-gone/hollow-knight/off", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("off status = %d", resp.StatusCode)
	}
	record, found, err := ts.daemon.Store.GetCloudScopeGone("hollow-knight")
	if err != nil || !found || record.Notice != "off" {
		t.Fatalf("notice = (%+v, %v, %v), want off", record, found, err)
	}
}

func TestCloudScopeGoneResume(t *testing.T) {
	ts := startTestServer(t)
	var mu sync.Mutex
	stillGone := true
	scopeGets := 0
	service := goneTestService(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || !strings.HasPrefix(r.URL.Path, "/v1/opensave/scopes/switch/hollow-knight") {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		mu.Lock()
		scopeGets++
		gone := stillGone
		mu.Unlock()
		if gone {
			writeGoneResponse(w, "scope_deleted")
			return
		}
		_ = json.NewEncoder(w).Encode(cloud.GameSaveScope{Type: "switch", GameID: "hollow-knight"})
	})
	configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
	putGoneAPITest(t, ts, "hollow-knight", store.CloudScopeGone{Code: "scope_deleted", Notice: "off"})

	resp, body := ts.do(t, http.MethodPost, "/api/cloud/scope-gone/hollow-knight/resume", nil)
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("still-gone resume status = %d, body = %v", resp.StatusCode, body)
	}
	if got := string(body["error"]); got != `"the cloud still marks this game's save as deleted; clear the deletion mark on the cloud web page first"` {
		t.Fatalf("resume error = %s", got)
	}
	record, found, err := ts.daemon.Store.GetCloudScopeGone("hollow-knight")
	if err != nil || !found {
		t.Fatalf("resume refused record = (%+v, %v, %v)", record, found, err)
	}
	if record.Notice != "pending" || !record.ResumeRefused {
		t.Fatalf("resume refused record = %+v, want pending+refused", record)
	}

	mu.Lock()
	stillGone = false
	mu.Unlock()
	resp, body = ts.do(t, http.MethodPost, "/api/cloud/scope-gone/hollow-knight/resume", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("recovered resume status = %d, body = %v", resp.StatusCode, body)
	}
	if len(body["state"]) == 0 || string(body["state"]) == `""` {
		t.Fatalf("resume state = %s", body["state"])
	}
	if _, found, err := ts.daemon.Store.GetCloudScopeGone("hollow-knight"); err != nil || found {
		t.Fatalf("resume success left a gone record: found=%v err=%v", found, err)
	}
	mu.Lock()
	gets := scopeGets
	mu.Unlock()
	if gets < 3 {
		t.Fatalf("resume/status scope GETs = %d, want at least the refused and recovered reads", gets)
	}
}

func TestCloudScopeGoneResumeOtherErrorKeepsRecordDeleted(t *testing.T) {
	ts := startTestServer(t)
	service := goneTestService(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})
	configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
	putGoneAPITest(t, ts, "hollow-knight", store.CloudScopeGone{Code: "scope_deleted"})

	resp, _ := ts.do(t, http.MethodPost, "/api/cloud/scope-gone/hollow-knight/resume", nil)
	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("other-error resume status = %d, want 502", resp.StatusCode)
	}
	if _, found, _ := ts.daemon.Store.GetCloudScopeGone("hollow-knight"); found {
		t.Fatal("resume failure restored the gone record")
	}
}

func TestCloudDeleteGameRefusesGoneScope(t *testing.T) {
	t.Run("recorded locally", func(t *testing.T) {
		ts := startTestServer(t)
		var mu sync.Mutex
		requests := 0
		deletes := 0
		service := goneTestService(t, func(w http.ResponseWriter, r *http.Request) {
			mu.Lock()
			requests++
			if r.Method == http.MethodDelete {
				deletes++
			}
			mu.Unlock()
			w.WriteHeader(http.StatusNotFound)
		})
		configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
		putGoneAPITest(t, ts, "hollow-knight", store.CloudScopeGone{Code: "scope_deleted"})

		resp, body := ts.do(t, http.MethodPost, "/api/cloud/delete-game/hollow-knight", nil)
		if resp.StatusCode != http.StatusConflict {
			t.Fatalf("delete status = %d, body = %v", resp.StatusCode, body)
		}
		if got := string(body["error"]); got != `"this game's cloud save was deleted or merged on the server; its cloud copies are left as they are"` {
			t.Fatalf("delete error = %s", got)
		}
		mu.Lock()
		req, del := requests, deletes
		mu.Unlock()
		if req != 0 || del != 0 {
			t.Fatalf("local gone record still reached the service: requests=%d deletes=%d", req, del)
		}
	})

	t.Run("local game already removed", func(t *testing.T) {
		ts := startTestServer(t)
		var mu sync.Mutex
		deletes := 0
		service := goneTestService(t, func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodDelete {
				mu.Lock()
				deletes++
				mu.Unlock()
				w.WriteHeader(http.StatusNoContent)
				return
			}
			if r.Method == http.MethodGet && r.URL.Path == "/v1/opensave/scopes/switch/hollow-knight" {
				writeGoneResponse(w, "scope_retired")
				return
			}
			w.WriteHeader(http.StatusNotFound)
		})
		configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
		if err := ts.daemon.Store.DeleteGame("hollow-knight"); err != nil {
			t.Fatal(err)
		}

		resp, body := ts.do(t, http.MethodPost, "/api/cloud/delete-game/hollow-knight", nil)
		if resp.StatusCode != http.StatusConflict {
			t.Fatalf("delete status = %d, body = %v", resp.StatusCode, body)
		}
		mu.Lock()
		del := deletes
		mu.Unlock()
		if del != 0 {
			t.Fatalf("delete requests = %d, want none", del)
		}
	})

	t.Run("server scope retired after untrack", func(t *testing.T) {
		ts := startTestServer(t)
		var mu sync.Mutex
		deletes := 0
		scopeGets := 0
		service := goneTestService(t, func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodDelete {
				mu.Lock()
				deletes++
				mu.Unlock()
				w.WriteHeader(http.StatusNoContent)
				return
			}
			if r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/v1/opensave/scopes/") {
				mu.Lock()
				scopeGets++
				mu.Unlock()
				writeGoneResponse(w, "scope_retired")
				return
			}
			w.WriteHeader(http.StatusNotFound)
		})
		configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")

		resp, body := ts.do(t, http.MethodPost, "/api/cloud/delete-game/hollow-knight", nil)
		if resp.StatusCode != http.StatusConflict {
			t.Fatalf("delete status = %d, body = %v", resp.StatusCode, body)
		}
		if got := string(body["error"]); got != `"this game's cloud save was deleted or merged on the server; its cloud copies are left as they are"` {
			t.Fatalf("delete error = %s", got)
		}
		mu.Lock()
		del, gets := deletes, scopeGets
		mu.Unlock()
		if del != 0 {
			t.Fatalf("delete requests = %d, want none", del)
		}
		if gets != 1 {
			t.Fatalf("scope GETs = %d, want one before deleting", gets)
		}
	})
}

func TestUntrackRemovesGoneRecord(t *testing.T) {
	ts := startTestServer(t)
	service := goneTestService(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	})
	configureGoneAPITest(t, ts, service.URL, "hollow-knight", "Hollow Knight")
	putGoneAPITest(t, ts, "hollow-knight", store.CloudScopeGone{Code: "scope_deleted"})

	resp, body := ts.do(t, http.MethodDelete, "/api/games/hollow-knight", nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("untrack status = %d, body = %v", resp.StatusCode, body)
	}
	if _, found, err := ts.daemon.Store.GetCloudScopeGone("hollow-knight"); err != nil || found {
		t.Fatalf("gone record survived untrack: found=%v err=%v", found, err)
	}
}
