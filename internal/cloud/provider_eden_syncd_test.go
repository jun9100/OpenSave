package cloud

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path"
	"sort"
	"strings"
	"sync"
	"testing"

	"github.com/opensave/opensave/internal/store"
)

func TestNormalizeEdenSyncdURL(t *testing.T) {
	for _, tc := range []struct {
		name  string
		input string
		want  string
		bad   bool
	}{
		{name: "service root", input: "https://nas.example:8788", want: "https://nas.example:8788/opensave"},
		{name: "service root trailing slash", input: "https://nas.example:8788/", want: "https://nas.example:8788/opensave"},
		{name: "already a collection", input: "http://nas.example/opensave/", want: "http://nas.example/opensave"},
		{name: "reverse proxy base", input: "https://nas.example/eden", want: "https://nas.example/eden/opensave"},
		{name: "empty during setup", input: "", want: ""},
		{name: "missing scheme", input: "nas.example", bad: true},
		{name: "embedded credentials", input: "https://u:p@nas.example", bad: true},
		{name: "query", input: "https://nas.example?x=1", bad: true},
		{name: "relative segment", input: "https://nas.example/a/../b", bad: true},
		{name: "encoded separator", input: "https://nas.example/%2f", bad: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := NormalizeEdenSyncdURL(tc.input)
			if tc.bad {
				if err == nil {
					t.Fatalf("NormalizeEdenSyncdURL(%q) = %q, want error", tc.input, got)
				}
				return
			}
			if err != nil || got != tc.want {
				t.Fatalf("NormalizeEdenSyncdURL(%q) = %q, %v; want %q", tc.input, got, err, tc.want)
			}
		})
	}
}

func TestEdenSyncdProviderRoundTrip(t *testing.T) {
	const username = "opensave"
	const password = "test-password"

	var mu sync.Mutex
	files := map[string][]byte{}
	var paths []string

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if gotUser, gotPassword, ok := r.BasicAuth(); !ok || gotUser != username || gotPassword != password {
			w.Header().Set("WWW-Authenticate", `Basic realm="EDEN_SYNCD OpenSave"`)
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.URL.Path != "/opensave" && !strings.HasPrefix(r.URL.Path, "/opensave/") {
			w.WriteHeader(http.StatusNotFound)
			return
		}

		mu.Lock()
		paths = append(paths, r.URL.Path)
		mu.Unlock()

		name := path.Base(r.URL.Path)
		switch r.Method {
		case "PROPFIND":
			mu.Lock()
			names := make([]string, 0, len(files))
			for fileName := range files {
				names = append(names, fileName)
			}
			sort.Strings(names)
			w.Header().Set("Content-Type", "application/xml; charset=utf-8")
			w.WriteHeader(http.StatusMultiStatus)
			_, _ = fmt.Fprint(w, `<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>/opensave/</D:href><D:propstat><D:prop><D:getcontentlength>0</D:getcontentlength></D:prop></D:propstat></D:response>`)
			for _, fileName := range names {
				_, _ = fmt.Fprintf(w, `<D:response><D:href>/opensave/%s</D:href><D:propstat><D:prop><D:getcontentlength>%d</D:getcontentlength><D:getlastmodified>Wed, 01 Jul 2026 10:00:00 GMT</D:getlastmodified></D:prop></D:propstat></D:response>`, fileName, len(files[fileName]))
			}
			_, _ = fmt.Fprint(w, `</D:multistatus>`)
			mu.Unlock()
		case http.MethodPut:
			body, _ := io.ReadAll(r.Body)
			mu.Lock()
			files[name] = body
			mu.Unlock()
			w.WriteHeader(http.StatusCreated)
		case http.MethodHead:
			mu.Lock()
			body, ok := files[name]
			mu.Unlock()
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			w.Header().Set("Content-Length", fmt.Sprint(len(body)))
			w.WriteHeader(http.StatusOK)
		case http.MethodGet:
			mu.Lock()
			body, ok := files[name]
			mu.Unlock()
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			_, _ = w.Write(body)
		case http.MethodDelete:
			mu.Lock()
			_, ok := files[name]
			delete(files, name)
			mu.Unlock()
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusMethodNotAllowed)
		}
	}))
	defer server.Close()

	svc, data := newTestService(t)
	setCloudConfig(t, data, func(config *store.CloudConfig) {
		config.Enabled = true
		config.Provider = "eden_syncd"
		config.URL = server.URL
		config.Username = username
		config.Password = password
	})

	const remoteName = "hollow-knight__main__snap_1.zip"
	if err := svc.Upload(writeTempZip(t, "snapshot bytes"), remoteName); err != nil {
		t.Fatalf("Upload: %v", err)
	}
	filesAfterUpload, err := svc.List()
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(filesAfterUpload) != 1 || filesAfterUpload[0].Name != remoteName || filesAfterUpload[0].SizeBytes != int64(len("snapshot bytes")) {
		t.Fatalf("List = %+v", filesAfterUpload)
	}

	destination := path.Join(t.TempDir(), "downloaded.zip")
	if err := svc.Download(remoteName, destination); err != nil {
		t.Fatalf("Download: %v", err)
	}
	if got, err := os.ReadFile(destination); err != nil || string(got) != "snapshot bytes" {
		t.Fatalf("downloaded = %q, %v", got, err)
	}
	if err := svc.Delete(filesAfterUpload[0]); err != nil {
		t.Fatalf("Delete: %v", err)
	}

	mu.Lock()
	defer mu.Unlock()
	for _, requestPath := range paths {
		if requestPath != "/opensave" && !strings.HasPrefix(requestPath, "/opensave/") {
			t.Errorf("request path = %q, want /opensave collection", requestPath)
		}
	}
}
