package cloud

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/store"
)

func writeScopeGoneJSON(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	body := map[string]any{"code": code, "error": "server explanation"}
	if code == "scope_deleted" {
		body["deletedAt"] = "2026-11-09T12:00:00Z"
	}
	if code == "scope_retired" {
		body["successor"] = map[string]string{"type": "pc", "gameId": "forza-horizon-5"}
	}
	_ = json.NewEncoder(w).Encode(body)
}

func TestGameSaveScopeGoneOnEveryPath(t *testing.T) {
	cases := []struct {
		name   string
		code   string
		method string
		path   string
		call   func(*Service) error
	}{
		{
			name: "get deleted", code: "scope_deleted", method: http.MethodGet,
			path: "/v1/opensave/scopes/pc/hollow-knight",
			call: func(svc *Service) error {
				_, err := svc.GetGameSaveScope("pc", "hollow-knight")
				return err
			},
		},
		{
			name: "snapshot retired", code: "scope_retired", method: http.MethodPut,
			path: "/v1/opensave/scopes/pc/hollow-knight/snapshots/snap_1--dev-a",
			call: func(svc *Service) error {
				_, err := svc.PutGameSaveScopeSnapshot("pc", "hollow-knight", GameSaveScopeCandidate{
					ID: "snap_1--dev-a", Archive: "hollow-knight__main__snap_1--dev-a.zip",
				}, "")
				return err
			},
		},
		{
			name: "conflict deleted", code: "scope_deleted", method: http.MethodPut,
			path: "/v1/opensave/scopes/pc/hollow-knight/conflicts/snap_2--dev-a",
			call: func(svc *Service) error {
				_, err := svc.PutGameSaveScopeConflict("pc", "hollow-knight", GameSaveScopeCandidate{
					ID: "snap_2--dev-a", Archive: "hollow-knight__main__snap_2--dev-a.zip",
				})
				return err
			},
		},
		{
			name: "identity retired", code: "scope_retired", method: http.MethodPut,
			path: "/v1/opensave/scopes/pc/hollow-knight/identity",
			call: func(svc *Service) error {
				_, err := svc.PutGameSaveScopeIdentity("pc", "hollow-knight", GameSaveScopeIdentity{Name: "Hollow Knight"})
				return err
			},
		},
		{
			name: "upload deleted", code: "scope_deleted", method: http.MethodPut,
			path: "/v1/opensave/files/pc/hollow-knight__main__snap_3--dev-a.zip",
			call: func(svc *Service) error {
				return svc.Upload(writeTempZip(t, "zip bytes"), "hollow-knight__main__snap_3--dev-a.zip")
			},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != tc.method || r.URL.Path != tc.path {
					t.Errorf("request = %s %s, want %s %s", r.Method, r.URL.Path, tc.method, tc.path)
				}
				writeScopeGoneJSON(w, http.StatusGone, tc.code)
			}))
			defer server.Close()

			svc, data := newTestService(t)
			setCloudConfig(t, data, func(c *store.CloudConfig) {
				c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, "test-key"
			})
			err := tc.call(svc)
			if err == nil {
				t.Fatal("410 was returned as success")
			}
			var gone *GameSaveScopeGoneError
			if !errors.As(err, &gone) {
				t.Fatalf("error = %T %v, want GameSaveScopeGoneError", err, err)
			}
			if gone.Code != tc.code {
				t.Errorf("code = %q, want %q", gone.Code, tc.code)
			}
			if _, ok := IsGameSaveScopeGone(err); !ok {
				t.Errorf("IsGameSaveScopeGone(%v) = false", err)
			}
			switch tc.code {
			case "scope_deleted":
				if got := gone.Error(); got != "game-save scope was deleted on the server" {
					t.Errorf("deleted error = %q", got)
				}
				if !gone.DeletedAt.Equal(time.Date(2026, 11, 9, 12, 0, 0, 0, time.UTC)) {
					t.Errorf("deletedAt = %v", gone.DeletedAt)
				}
			case "scope_retired":
				if got := gone.Error(); got != "game-save scope was merged into another game on the server" {
					t.Errorf("retired error = %q", got)
				}
				if gone.SuccessorType != "pc" || gone.SuccessorGameID != "forza-horizon-5" {
					t.Errorf("successor = %q/%q", gone.SuccessorType, gone.SuccessorGameID)
				}
			}
		})
	}
}

func TestGameSaveScopeGoneUnknownCodeKeepsTypeButDoesNotStopSync(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeScopeGoneJSON(w, http.StatusGone, "scope_locked_forever")
	}))
	defer server.Close()

	svc, data := newTestService(t)
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, "test-key"
	})
	_, err := svc.GetGameSaveScope("pc", "hollow-knight")
	var gone *GameSaveScopeGoneError
	if !errors.As(err, &gone) {
		t.Fatalf("error = %T %v, want GameSaveScopeGoneError", err, err)
	}
	if gone.Code != "scope_locked_forever" || gone.Error() != "game-save refused this scope (HTTP 410)" {
		t.Fatalf("gone = %+v, error = %q", gone, gone.Error())
	}
	if _, ok := IsGameSaveScopeGone(err); ok {
		t.Fatal("unknown 410 code was treated as a known tombstone")
	}
}

func TestGameSaveScopeDecodesLockedAndRetired(t *testing.T) {
	const token = "test-key"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+token {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		now := time.Now().UTC()
		switch r.URL.Path {
		case "/v1/opensave/scopes/pc/hollow-knight":
			_ = json.NewEncoder(w).Encode(GameSaveScope{
				Type: "pc", GameID: "hollow-knight",
				Head: &GameSaveScopeSnapshot{
					GameSaveScopeCandidate: GameSaveScopeCandidate{ID: "snap-head", CreatedAt: now},
					ReceivedAt:             now,
					Locked:                 true,
				},
			})
		case "/v1/opensave/games":
			_ = json.NewEncoder(w).Encode(map[string]any{"games": []GameSaveGame{{
				Key: "pc/hollow-knight", Type: "pc", Name: "Hollow Knight",
				Scopes: []GameSaveGameMember{{
					Type: "pc", GameID: "hollow-knight", Name: "Hollow Knight", Retired: true,
				}},
			}}})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	svc, data := newTestService(t)
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, token
	})
	scope, err := svc.GetGameSaveScope("pc", "hollow-knight")
	if err != nil {
		t.Fatal(err)
	}
	if scope.Head == nil || !scope.Head.Locked {
		t.Fatalf("scope head = %+v, want locked", scope.Head)
	}
	games, err := svc.ListGameSaveGames()
	if err != nil {
		t.Fatal(err)
	}
	if len(games) != 1 || len(games[0].Scopes) != 1 || !games[0].Scopes[0].Retired {
		t.Fatalf("games = %+v, want a retired member", games)
	}
}
