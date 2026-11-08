package snapshot

import (
	"archive/zip"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/opensave/opensave/internal/ignore"
	"github.com/opensave/opensave/internal/store"
)

func mustReadForeign(t *testing.T, path string) []byte {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func writeForeignZip(t *testing.T, zipPath string, entries map[string]string) {
	t.Helper()
	f, err := os.Create(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	w := zip.NewWriter(f)
	names := make([]string, 0, len(entries))
	for name := range entries {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		entry, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte(entries[name])); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

func readForeignZip(t *testing.T, zipPath string) map[string]string {
	t.Helper()
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	out := map[string]string{}
	for _, f := range r.File {
		if f.FileInfo().IsDir() {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			t.Fatal(err)
		}
		raw, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatal(err)
		}
		out[f.Name] = string(raw)
	}
	return out
}

func foreignTree(t *testing.T, root string) map[string]string {
	t.Helper()
	out := map[string]string{}
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		raw, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		out[filepath.ToSlash(rel)] = string(raw)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func sameForeignTree(t *testing.T, want, got map[string]string) {
	t.Helper()
	if len(want) != len(got) {
		t.Fatalf("files changed: want %v, got %v", want, got)
	}
	for name, content := range want {
		if got[name] != content {
			t.Fatalf("file %q changed: want %q, got %q", name, content, got[name])
		}
	}
}

const foreignAndroidManifest = `{"version":1,"gameKey":"switch/title:0100000000010000","gameName":"Super Mario Odyssey","locations":[{"kind":"switch-title","relativePath":"a/b","archivePath":"location-0000","candidate":false},{"kind":"android-game","relativePath":"c","archivePath":"location-0001","candidate":false}]}`

func TestInspectForeignArchivePC(t *testing.T) {
	zipPath := filepath.Join(t.TempDir(), "pc.zip")
	writeForeignZip(t, zipPath, map[string]string{
		"slot1.sav":    "save",
		"sub/data.bin": strings.Repeat("x", 10),
		".opensave-locations/config/settings.ini": "cfg",
		".opensave-locations/config/":             "",
	})

	archive, err := InspectForeignArchive(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	if archive.Format != ForeignFormatPC {
		t.Fatalf("format = %q, want %q", archive.Format, ForeignFormatPC)
	}
	if len(archive.Locations) != 1 {
		t.Fatalf("locations = %+v, want one", archive.Locations)
	}
	root := archive.Locations[0]
	if root.ID != ForeignPCRoot || root.Kind != "" || root.RelativePath != "" {
		t.Fatalf("pc root = %+v", root)
	}
	if root.Files != 2 {
		t.Fatalf("pc root files = %d, want 2", root.Files)
	}
	if want := int64(len("save") + 10); root.Bytes != want {
		t.Fatalf("pc root bytes = %d, want %d", root.Bytes, want)
	}
	if archive.IgnoredEntries != 2 {
		t.Fatalf("ignored entries = %d, want 2", archive.IgnoredEntries)
	}
}

func TestInspectForeignArchiveAndroid(t *testing.T) {
	zipPath := filepath.Join(t.TempDir(), "android.zip")
	writeForeignZip(t, zipPath, map[string]string{
		ForeignManifestName:       foreignAndroidManifest,
		"location-0000/slot.sav":  "12345",
		"location-0000/sub/x":     "abc",
		"location-0001/other.dat": "abcd",
		"stray.txt":               "z",
	})

	archive, err := InspectForeignArchive(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	if archive.Format != ForeignFormatAndroid {
		t.Fatalf("format = %q, want %q", archive.Format, ForeignFormatAndroid)
	}
	if archive.GameKey != "switch/title:0100000000010000" || archive.GameName != "Super Mario Odyssey" {
		t.Fatalf("identity = %q / %q", archive.GameKey, archive.GameName)
	}
	if len(archive.Locations) != 2 {
		t.Fatalf("locations = %+v, want two", archive.Locations)
	}
	first, second := archive.Locations[0], archive.Locations[1]
	if first.ID != "location-0000" || first.Kind != "switch-title" || first.RelativePath != "a/b" {
		t.Fatalf("first location = %+v", first)
	}
	if first.Files != 2 || first.Bytes != int64(len("12345")+len("abc")) {
		t.Fatalf("first location stats = %+v", first)
	}
	if second.ID != "location-0001" || second.Kind != "android-game" || second.RelativePath != "c" {
		t.Fatalf("second location = %+v", second)
	}
	if second.Files != 1 || second.Bytes != int64(len("abcd")) {
		t.Fatalf("second location stats = %+v", second)
	}
	// The manifest describes the archive and is not an ignored save file.
	if archive.IgnoredEntries != 1 {
		t.Fatalf("ignored entries = %d, want 1", archive.IgnoredEntries)
	}
}

func TestInspectForeignArchiveRejectsBadManifests(t *testing.T) {
	cases := map[string]string{
		"version":                 `{"version":2,"gameKey":"k","gameName":"n","locations":[{"kind":"k","relativePath":"a","archivePath":"location-0000"}]}`,
		"archive path traversal":  `{"version":1,"gameKey":"k","gameName":"n","locations":[{"kind":"k","relativePath":"a","archivePath":"../x"}]}`,
		"duplicate archive path":  `{"version":1,"gameKey":"k","gameName":"n","locations":[{"kind":"k","relativePath":"a","archivePath":"location-0000"},{"kind":"k","relativePath":"b","archivePath":"location-0000"}]}`,
		"missing game key":        `{"version":1,"gameName":"n","locations":[{"kind":"k","relativePath":"a","archivePath":"location-0000"}]}`,
		"empty locations":         `{"version":1,"gameKey":"k","gameName":"n","locations":[]}`,
		"absolute relative path":  `{"version":1,"gameKey":"k","gameName":"n","locations":[{"kind":"k","relativePath":"/a","archivePath":"location-0000"}]}`,
		"relative path parent":    `{"version":1,"gameKey":"k","gameName":"n","locations":[{"kind":"k","relativePath":"a/../b","archivePath":"location-0000"}]}`,
		"archive path characters": `{"version":1,"gameKey":"k","gameName":"n","locations":[{"kind":"k","relativePath":"a","archivePath":"location-0000/x"}]}`,
	}
	for name, manifest := range cases {
		t.Run(name, func(t *testing.T) {
			zipPath := filepath.Join(t.TempDir(), "bad.zip")
			writeForeignZip(t, zipPath, map[string]string{ForeignManifestName: manifest})
			if _, err := InspectForeignArchive(zipPath); err == nil {
				t.Fatal("InspectForeignArchive accepted an invalid manifest")
			}
		})
	}
}

func TestInspectForeignArchiveRejectsZipSlip(t *testing.T) {
	cases := map[string]map[string]string{
		"parent":       {"../evil.txt": "x"},
		"absolute":     {"/abs.txt": "x"},
		"drive letter": {"C:/evil.txt": "x"},
		"location parent": {
			ForeignManifestName:            foreignAndroidManifest,
			"location-0000/../../evil.txt": "x",
		},
		"location drive letter": {
			ForeignManifestName:         foreignAndroidManifest,
			"location-0000/C:/evil.txt": "x",
		},
	}
	for name, entries := range cases {
		t.Run(name, func(t *testing.T) {
			zipPath := filepath.Join(t.TempDir(), "slip.zip")
			writeForeignZip(t, zipPath, entries)
			if _, err := InspectForeignArchive(zipPath); err == nil {
				t.Fatal("InspectForeignArchive accepted an escaping entry")
			}
		})
	}
}

func TestRestoreForeignPCRootReplacesMainAndKeepsLocalFiles(t *testing.T) {
	env := setup(t)
	configDir := filepath.Join(t.TempDir(), "config")
	if err := os.MkdirAll(configDir, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := env.store.AddGameRoot("game1", "config", configDir); err != nil {
		t.Fatal(err)
	}
	writeSave(t, env.saveDir, "slot1.sav", "old save")
	writeSave(t, env.saveDir, "obsolete.dat", "delete me")
	writeSave(t, env.saveDir, "local.cfg", "device specific")
	writeSave(t, configDir, "settings.ini", "local config")

	zipPath := filepath.Join(t.TempDir(), "foreign.zip")
	writeForeignZip(t, zipPath, map[string]string{
		"slot1.sav": "new save",
		"new.dat":   "new file",
		".opensave-locations/config/settings.ini": "from archive",
		".opensave-locations/other/x":             "ignored",
	})

	restoredCalled := false
	env.mgr.OnRestored = func(store.Snapshot) { restoredCalled = true }

	safety, err := env.mgr.RestoreForeign("game1", zipPath,
		[]ForeignAssignment{{ArchiveLocation: ForeignPCRoot, LocalRoot: ""}},
		ignore.Parse("local.cfg"), "test safety")
	if err != nil {
		t.Fatal(err)
	}

	if got := string(mustReadForeign(t, filepath.Join(env.saveDir, "slot1.sav"))); got != "new save" {
		t.Fatalf("slot1.sav = %q", got)
	}
	if got := string(mustReadForeign(t, filepath.Join(env.saveDir, "new.dat"))); got != "new file" {
		t.Fatalf("new.dat = %q", got)
	}
	if _, err := os.Stat(filepath.Join(env.saveDir, "obsolete.dat")); !os.IsNotExist(err) {
		t.Fatalf("obsolete.dat was not removed: %v", err)
	}
	if got := string(mustReadForeign(t, filepath.Join(env.saveDir, "local.cfg"))); got != "device specific" {
		t.Fatalf("excluded local file = %q", got)
	}
	if got := string(mustReadForeign(t, filepath.Join(configDir, "settings.ini"))); got != "local config" {
		t.Fatalf("extra location changed: %q", got)
	}
	if _, err := os.Stat(filepath.Join(env.saveDir, ".opensave-locations")); !os.IsNotExist(err) {
		t.Fatalf("extra locations were extracted into the main save: %v", err)
	}
	if restoredCalled {
		t.Fatal("RestoreForeign called OnRestored, which would rewrite this device's cloud lineage")
	}

	snaps, err := env.store.ListSnapshots("game1", "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(snaps) != 1 || snaps[0].ID != safety.ID {
		t.Fatalf("snapshots = %+v, want only the safety copy", snaps)
	}
	if safety.Comment != "test safety" || !safety.IsSystemAuto {
		t.Fatalf("safety snapshot = %+v", safety)
	}
	safetyFiles := readForeignZip(t, safety.ZipPath)
	if safetyFiles["slot1.sav"] != "old save" || safetyFiles["obsolete.dat"] != "delete me" ||
		safetyFiles["local.cfg"] != "device specific" ||
		safetyFiles[".opensave-locations/config/settings.ini"] != "local config" {
		t.Fatalf("safety archive does not hold the old save: %+v", safetyFiles)
	}
	if _, ok := safetyFiles["new.dat"]; ok {
		t.Fatalf("safety archive already holds the restored file: %+v", safetyFiles)
	}
}

func TestRestoreForeignAndroidLocationGoesToChosenFolder(t *testing.T) {
	env := setup(t)
	configDir := filepath.Join(t.TempDir(), "config")
	if err := os.MkdirAll(configDir, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := env.store.AddGameRoot("game1", "config", configDir); err != nil {
		t.Fatal(err)
	}
	writeSave(t, env.saveDir, "main.dat", "main old")
	writeSave(t, configDir, "cfg.dat", "cfg old")

	zipPath := filepath.Join(t.TempDir(), "android.zip")
	writeForeignZip(t, zipPath, map[string]string{
		ForeignManifestName:       foreignAndroidManifest,
		"location-0000/slot.sav":  "android save",
		"location-0001/other.dat": "other location",
	})

	restoredCalled := false
	env.mgr.OnRestored = func(store.Snapshot) { restoredCalled = true }

	safety, err := env.mgr.RestoreForeign("game1", zipPath,
		[]ForeignAssignment{{ArchiveLocation: "location-0000", LocalRoot: "config"}},
		ignore.Rules{}, "android safety")
	if err != nil {
		t.Fatal(err)
	}

	if got := string(mustReadForeign(t, filepath.Join(configDir, "slot.sav"))); got != "android save" {
		t.Fatalf("restored location file = %q", got)
	}
	if _, err := os.Stat(filepath.Join(configDir, "cfg.dat")); !os.IsNotExist(err) {
		t.Fatalf("target location was not cleared: %v", err)
	}
	if got := string(mustReadForeign(t, filepath.Join(env.saveDir, "main.dat"))); got != "main old" {
		t.Fatalf("main save changed: %q", got)
	}
	if _, err := os.Stat(filepath.Join(env.saveDir, "slot.sav")); !os.IsNotExist(err) {
		t.Fatalf("mapped extra location was extracted into the main save: %v", err)
	}
	for _, path := range []string{
		filepath.Join(env.saveDir, "other.dat"),
		filepath.Join(configDir, "other.dat"),
		filepath.Join(env.saveDir, ForeignManifestName),
		filepath.Join(configDir, ForeignManifestName),
	} {
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("unmapped archive content %q was restored: %v", path, err)
		}
	}
	if restoredCalled {
		t.Fatal("RestoreForeign called OnRestored")
	}

	safetyFiles := readForeignZip(t, safety.ZipPath)
	if safetyFiles["main.dat"] != "main old" ||
		safetyFiles[".opensave-locations/config/cfg.dat"] != "cfg old" {
		t.Fatalf("safety archive does not hold the old save: %+v", safetyFiles)
	}
}

func TestRestoreForeignAbortsWhenSafetySnapshotFails(t *testing.T) {
	env := setup(t)
	configDir := filepath.Join(t.TempDir(), "config")
	if err := os.MkdirAll(configDir, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := env.store.AddGameRoot("game1", "config", configDir); err != nil {
		t.Fatal(err)
	}
	writeSave(t, env.saveDir, "slot1.sav", "old save")
	writeSave(t, env.saveDir, "keep.dat", "keep me")
	writeSave(t, configDir, "settings.ini", "local config")
	before := foreignTree(t, env.saveDir)
	beforeConfig := foreignTree(t, configDir)
	snapsBefore, err := env.store.ListSnapshots("game1", "main")
	if err != nil {
		t.Fatal(err)
	}

	zipPath := filepath.Join(t.TempDir(), "foreign.zip")
	writeForeignZip(t, zipPath, map[string]string{"slot1.sav": "new save"})

	env.mgr.beforeForeignSafety = func() error { return errors.New("injected safety failure") }
	defer func() { env.mgr.beforeForeignSafety = nil }()

	if _, err := env.mgr.RestoreForeign("game1", zipPath,
		[]ForeignAssignment{{ArchiveLocation: ForeignPCRoot, LocalRoot: ""}},
		ignore.Rules{}, "safety"); err == nil {
		t.Fatal("RestoreForeign succeeded although the safety snapshot failed")
	}

	sameForeignTree(t, before, foreignTree(t, env.saveDir))
	sameForeignTree(t, beforeConfig, foreignTree(t, configDir))
	snapsAfter, err := env.store.ListSnapshots("game1", "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(snapsAfter) != len(snapsBefore) {
		t.Fatalf("snapshots changed after a failed safety copy: before %d, after %d", len(snapsBefore), len(snapsAfter))
	}
}

func TestRestoreForeignRejectsInvalidAssignments(t *testing.T) {
	env := setup(t)
	configDir := filepath.Join(t.TempDir(), "config")
	if err := os.MkdirAll(configDir, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := env.store.AddGameRoot("game1", "config", configDir); err != nil {
		t.Fatal(err)
	}
	if err := env.store.NoteGameRoot("game1", "notes"); err != nil {
		t.Fatal(err)
	}
	singleFile := filepath.Join(t.TempDir(), "save.dat")
	if err := env.store.AddGameRoot("game1", "single", singleFile); err != nil {
		t.Fatal(err)
	}
	writeSave(t, env.saveDir, "slot1.sav", "old save")
	writeSave(t, configDir, "cfg.dat", "cfg old")
	before := foreignTree(t, env.saveDir)
	beforeConfig := foreignTree(t, configDir)
	snapsBefore, err := env.store.ListSnapshots("game1", "main")
	if err != nil {
		t.Fatal(err)
	}

	zipPath := filepath.Join(t.TempDir(), "android.zip")
	writeForeignZip(t, zipPath, map[string]string{
		ForeignManifestName:       foreignAndroidManifest,
		"location-0000/slot.sav":  "android save",
		"location-0001/other.dat": "other location",
	})

	cases := map[string][]ForeignAssignment{
		"empty": {
			{},
		},
		"duplicate local": {
			{ArchiveLocation: "location-0000", LocalRoot: ""},
			{ArchiveLocation: "location-0001", LocalRoot: ""},
		},
		"duplicate archive": {
			{ArchiveLocation: "location-0000", LocalRoot: ""},
			{ArchiveLocation: "location-0000", LocalRoot: "config"},
		},
		"missing archive location": {
			{ArchiveLocation: "location-9999", LocalRoot: ""},
		},
		"unmapped local location": {
			{ArchiveLocation: "location-0000", LocalRoot: "notes"},
		},
		"single-file target": {
			{ArchiveLocation: "location-0000", LocalRoot: "single"},
		},
	}
	for name, assignments := range cases {
		t.Run(name, func(t *testing.T) {
			if name == "empty" {
				assignments = nil
			}
			_, err := env.mgr.RestoreForeign("game1", zipPath, assignments, ignore.Rules{}, "safety")
			if err == nil || !errors.Is(err, ErrForeignAssignment) {
				t.Fatalf("RestoreForeign error = %v, want ErrForeignAssignment", err)
			}
			sameForeignTree(t, before, foreignTree(t, env.saveDir))
			sameForeignTree(t, beforeConfig, foreignTree(t, configDir))
			snapsAfter, err := env.store.ListSnapshots("game1", "main")
			if err != nil {
				t.Fatal(err)
			}
			if len(snapsAfter) != len(snapsBefore) {
				t.Fatalf("a rejected mapping created a snapshot: before %d, after %d", len(snapsBefore), len(snapsAfter))
			}
		})
	}
}
