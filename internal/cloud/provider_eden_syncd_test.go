package cloud

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/store"
)

func TestNormalizeEdenSyncdURL(t *testing.T) {
	for _, tc := range []struct {
		in, want string
		bad      bool
	}{
		{"https://nas.example:8788", "https://nas.example:8788", false},
		{"https://nas.example:8788/", "https://nas.example:8788", false},
		{"http://nas.example/opensave/", "http://nas.example", false},
		{"https://nas.example/eden", "https://nas.example/eden", false},
		{"", "", false},
		{"nas.example", "", true},
		{"https://u:p@nas.example", "", true},
	} {
		got, err := NormalizeEdenSyncdURL(tc.in)
		if tc.bad && err == nil {
			t.Errorf("NormalizeEdenSyncdURL(%q) accepted invalid URL", tc.in)
		}
		if !tc.bad && (err != nil || got != tc.want) {
			t.Errorf("NormalizeEdenSyncdURL(%q) = %q, %v; want %q", tc.in, got, err, tc.want)
		}
	}
}

func TestEdenSyncdProviderUsesNativeTypedAPI(t *testing.T) {
	const token = "sync-key"
	var mu sync.Mutex
	files := map[string][]byte{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+token {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.URL.Path == "/v1/opensave/files" && r.Method == http.MethodGet {
			mu.Lock()
			defer mu.Unlock()
			items := make([]map[string]any, 0, len(files))
			for key, body := range files {
				parts := strings.SplitN(key, "/", 2)
				items = append(items, map[string]any{"type": parts[0], "name": parts[1], "sizeBytes": len(body), "createdTime": time.Now().UTC()})
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"files": items})
			return
		}
		parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/opensave/files/"), "/")
		if len(parts) != 2 || r.URL.Path == "/v1/opensave/files/" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		key := parts[0] + "/" + parts[1]
		switch r.Method {
		case http.MethodPut:
			body, _ := io.ReadAll(r.Body)
			mu.Lock()
			files[key] = body
			mu.Unlock()
			w.WriteHeader(http.StatusCreated)
		case http.MethodGet:
			mu.Lock()
			body, ok := files[key]
			mu.Unlock()
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			_, _ = w.Write(body)
		case http.MethodDelete:
			mu.Lock()
			delete(files, key)
			mu.Unlock()
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusMethodNotAllowed)
		}
	}))
	defer server.Close()

	svc, data := newTestService(t)
	if err := data.CreateGame(store.Game{ID: "hollow-knight", Name: "Hollow Knight", SavePath: t.TempDir(), SaveType: "switch"}); err != nil {
		t.Fatal(err)
	}
	setCloudConfig(t, data, func(c *store.CloudConfig) {
		c.Enabled, c.Provider, c.URL, c.Password = true, "eden_syncd", server.URL, token
	})
	const name = "hollow-knight__main__snap_1.zip"
	if err := svc.Upload(writeTempZip(t, "snapshot bytes"), name); err != nil {
		t.Fatal(err)
	}
	items, err := svc.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].Name != name || items[0].ID != "switch" {
		t.Fatalf("List = %+v", items)
	}
	destination := path.Join(t.TempDir(), "download.zip")
	if err := svc.Download(name, destination); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(destination); string(got) != "snapshot bytes" {
		t.Fatalf("download = %q", got)
	}
	if err := svc.Delete(items[0]); err != nil {
		t.Fatal(err)
	}
}
