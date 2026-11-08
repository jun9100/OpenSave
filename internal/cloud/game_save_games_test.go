package cloud

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/store"
)

func TestListGameSaveGamesReadsGroupingIndex(t *testing.T) {
	const token = "games-test-token"
	received := time.Date(2026, 11, 8, 10, 0, 0, 0, time.UTC)
	promoted := time.Date(2026, 11, 9, 11, 0, 0, 0, time.UTC)

	var requests int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.Method != http.MethodGet {
			t.Errorf("ListGameSaveGames sent %s, want only GET", r.Method)
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if r.URL.Path != "/v1/opensave/games" {
			t.Errorf("ListGameSaveGames path = %q", r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if got := r.Header.Get("Authorization"); got != "Bearer "+token {
			t.Errorf("Authorization = %q", got)
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if got := r.Header.Get("X-OpenSave-Device-ID"); got != "" {
			t.Errorf("read-only games request sent device id %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"games":[{"key":"switch/title:0100000000010000","type":"switch","name":"Super Mario Odyssey","lastReceivedAt":"` + received.Format(time.RFC3339) + `","scopes":[{"type":"switch","gameId":"android-switch-ab12","link":"auto","name":"Super Mario Odyssey","head":{"id":"snap-head","archive":"game__main__snap-head.zip","parent":"","contentDigest":"` + strings.Repeat("a", 64) + `","createdAt":"` + received.Format(time.RFC3339) + `","device":"android-node","deviceName":"Pixel 8","platform":"android","receivedAt":"` + received.Format(time.RFC3339) + `","conflict":false,"promotedAt":"` + promoted.Format(time.RFC3339) + `"},"snapshotCount":3,"conflictCount":0,"lastReceivedAt":"` + received.Format(time.RFC3339) + `"},{"type":"switch","gameId":"desktop","link":"none","head":null,"snapshotCount":1,"conflictCount":2}]}]}`))
	}))
	defer server.Close()

	svc, data := newTestService(t)
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, token
	})

	games, err := svc.ListGameSaveGames()
	if err != nil {
		t.Fatal(err)
	}
	if requests != 1 {
		t.Fatalf("requests = %d, want 1", requests)
	}
	if len(games) != 1 {
		t.Fatalf("games = %+v", games)
	}
	game := games[0]
	if game.Key != "switch/title:0100000000010000" || game.Type != "switch" ||
		game.Name != "Super Mario Odyssey" || !game.LastReceivedAt.Equal(received) {
		t.Fatalf("game = %+v", game)
	}
	if len(game.Scopes) != 2 {
		t.Fatalf("scopes = %+v", game.Scopes)
	}
	head := game.Scopes[0]
	if head.GameID != "android-switch-ab12" || head.Link != "auto" || head.Name != "Super Mario Odyssey" ||
		head.SnapshotCount != 3 || head.ConflictCount != 0 || !head.LastReceivedAt.Equal(received) {
		t.Fatalf("head member = %+v", head)
	}
	if head.Head == nil {
		t.Fatal("head member lost its snapshot head")
	}
	if head.Head.ID != "snap-head" || head.Head.Archive != "game__main__snap-head.zip" ||
		head.Head.DeviceName != "Pixel 8" || head.Head.Platform != "android" ||
		!head.Head.ReceivedAt.Equal(received) || !head.Head.PromotedAt.Equal(promoted) {
		t.Fatalf("member head = %+v", head.Head)
	}
	if game.Scopes[1].Head != nil {
		t.Fatalf("null head became %+v", game.Scopes[1].Head)
	}
}

func TestListGameSaveGamesOnlyRoute404IsUnsupported(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		want   error
	}{
		{"missing route", http.StatusNotFound, ErrGameSaveGamesUnsupported},
		{"server error", http.StatusInternalServerError, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodGet {
					t.Errorf("ListGameSaveGames sent %s, want only GET", r.Method)
				}
				w.WriteHeader(tc.status)
			}))
			defer server.Close()

			svc, data := newTestService(t)
			setCloudConfig(t, data, func(c *store.CloudConfig) {
				c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, "token"
			})
			games, err := svc.ListGameSaveGames()
			if err == nil {
				t.Fatalf("ListGameSaveGames = %+v, want an error", games)
			}
			if tc.want != nil && !errors.Is(err, tc.want) {
				t.Fatalf("error = %v, want %v", err, tc.want)
			}
			if tc.want == nil && errors.Is(err, ErrGameSaveGamesUnsupported) {
				t.Fatalf("HTTP %d was treated as a missing route", tc.status)
			}
		})
	}
}

func TestListGameSaveGamesMissingListIsEmpty(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			t.Errorf("ListGameSaveGames sent %s, want only GET", r.Method)
		}
		_, _ = w.Write([]byte(`{}`))
	}))
	defer server.Close()

	svc, data := newTestService(t)
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, GameSaveProvider, server.URL, "token"
	})
	games, err := svc.ListGameSaveGames()
	if err != nil {
		t.Fatal(err)
	}
	if games == nil || len(games) != 0 {
		t.Fatalf("games = %#v, want an empty non-nil list", games)
	}
	raw, err := json.Marshal(games)
	if err != nil || string(raw) != "[]" {
		t.Fatalf("empty games JSON = %s, %v", raw, err)
	}
}
