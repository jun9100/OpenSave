package snapshot

import (
	"archive/zip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/opensave/opensave/internal/delta"
	"github.com/opensave/opensave/internal/ignore"
	"github.com/opensave/opensave/internal/store"
)

// Restoring a save that another device made.
//
// Two archive layouts exist. This client has always written the PC one: the
// save folder's contents at the ZIP root, extra locations under RootPrefix.
// Android writes a manifest at the root that names each location and where
// its files live inside the archive. A cross-device restore cannot hand the
// Android layout to the ordinary restore path, which would extract the
// manifest and every location folder as if they were save files.
const (
	ForeignFormatPC      = "pc"
	ForeignFormatAndroid = "android"
	// ForeignPCRoot is the only archive location a PC-format archive has.
	ForeignPCRoot = "pc-root"
	// ForeignManifestName marks an Android-format archive. Its absence means
	// the archive is PC format.
	ForeignManifestName = "opensave-game.json"
)

// ForeignLocation is one location inside an archive, as the plan presents it.
type ForeignLocation struct {
	ID           string `json:"id"`
	Kind         string `json:"kind"`
	RelativePath string `json:"relativePath"`
	Files        int    `json:"files"`
	Bytes        int64  `json:"bytes"`
}

// ForeignArchive is what InspectForeignArchive found: the layout, the game it
// belongs to (Android only), and the locations a restore can choose from.
type ForeignArchive struct {
	Format         string            `json:"format"`
	GameKey        string            `json:"gameKey"`
	GameName       string            `json:"gameName"`
	Locations      []ForeignLocation `json:"locations"`
	IgnoredEntries int               `json:"ignoredEntries"`
}

// ForeignAssignment maps one archive location to one local save location.
// LocalRoot is "" for the game's main save folder, otherwise a named extra
// location.
type ForeignAssignment struct {
	ArchiveLocation string `json:"archiveLocation"`
	LocalRoot       string `json:"localRoot"`
}

var (
	// ErrForeignArchive marks an archive whose layout cannot be trusted. The
	// API answers 400: nothing about the request can make it restorable.
	ErrForeignArchive = errors.New("cloud save archive is invalid")
	// ErrForeignAssignment marks a mapping that does not name a real archive
	// location or a folder this device can restore into.
	ErrForeignAssignment = errors.New("invalid cross-device restore assignment")
)

var foreignArchivePathRe = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

type foreignManifest struct {
	Version   int                       `json:"version"`
	GameKey   string                    `json:"gameKey"`
	GameName  string                    `json:"gameName"`
	Locations []foreignManifestLocation `json:"locations"`
}

type foreignManifestLocation struct {
	Kind         string `json:"kind"`
	RelativePath string `json:"relativePath"`
	ArchivePath  string `json:"archivePath"`
	Candidate    bool   `json:"candidate"`
}

// InspectForeignArchive reads an archive without changing anything and
// reports its layout and locations. It validates every entry name up front:
// a name that would escape its destination makes the whole archive unusable,
// rather than being skipped and leaving a partial restore to discover later.
func InspectForeignArchive(zipPath string) (ForeignArchive, error) {
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return ForeignArchive{}, fmt.Errorf("open save archive: %w", err)
	}
	defer r.Close()

	for _, f := range r.File {
		if unsafeForeignEntry(f.Name) {
			return ForeignArchive{}, fmt.Errorf("save archive entry %q escapes the destination", f.Name)
		}
	}

	var manifest *zip.File
	for _, f := range r.File {
		if f.Name == ForeignManifestName {
			manifest = f
			break
		}
	}
	if manifest != nil {
		return inspectAndroidArchive(r.File, manifest)
	}
	return inspectPCArchive(r.File), nil
}

func inspectPCArchive(files []*zip.File) ForeignArchive {
	root := ForeignLocation{ID: ForeignPCRoot}
	ignored := 0
	prefixDir := strings.TrimSuffix(RootPrefix, "/")
	for _, f := range files {
		// Extra locations belong to the device that wrote the archive. Their
		// names are not portable, so a cross-device restore leaves them out
		// entirely and only reports that they were there.
		if f.Name == prefixDir || strings.HasPrefix(f.Name, RootPrefix) {
			ignored++
			continue
		}
		if f.FileInfo().IsDir() {
			continue
		}
		root.Files++
		root.Bytes += int64(f.UncompressedSize64)
	}
	return ForeignArchive{
		Format:         ForeignFormatPC,
		Locations:      []ForeignLocation{root},
		IgnoredEntries: ignored,
	}
}

func inspectAndroidArchive(files []*zip.File, manifestFile *zip.File) (ForeignArchive, error) {
	manifest, err := readForeignManifest(manifestFile)
	if err != nil {
		return ForeignArchive{}, err
	}
	archive := ForeignArchive{
		Format:    ForeignFormatAndroid,
		GameKey:   manifest.GameKey,
		GameName:  manifest.GameName,
		Locations: make([]ForeignLocation, len(manifest.Locations)),
	}
	byPath := make(map[string]int, len(manifest.Locations))
	for i, location := range manifest.Locations {
		archive.Locations[i] = ForeignLocation{
			ID:           location.ArchivePath,
			Kind:         location.Kind,
			RelativePath: location.RelativePath,
		}
		byPath[location.ArchivePath] = i
	}

	for _, f := range files {
		// The manifest describes the archive; it is not save data and must
		// never be extracted into a save folder.
		if f.Name == ForeignManifestName {
			continue
		}
		matched := false
		for archivePath, index := range byPath {
			prefix := archivePath + "/"
			if !strings.HasPrefix(f.Name, prefix) {
				continue
			}
			rel := strings.TrimPrefix(f.Name, prefix)
			if unsafeForeignEntry(rel) {
				return ForeignArchive{}, fmt.Errorf("save archive entry %q escapes its location", f.Name)
			}
			matched = true
			if !f.FileInfo().IsDir() {
				archive.Locations[index].Files++
				archive.Locations[index].Bytes += int64(f.UncompressedSize64)
			}
			break
		}
		if !matched {
			archive.IgnoredEntries++
		}
	}
	return archive, nil
}

func readForeignManifest(f *zip.File) (foreignManifest, error) {
	rc, err := f.Open()
	if err != nil {
		return foreignManifest{}, fmt.Errorf("read %s: %w", ForeignManifestName, err)
	}
	defer rc.Close()
	raw, err := io.ReadAll(rc)
	if err != nil {
		return foreignManifest{}, fmt.Errorf("read %s: %w", ForeignManifestName, err)
	}

	var manifest foreignManifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		return foreignManifest{}, fmt.Errorf("read %s: %w", ForeignManifestName, err)
	}
	if manifest.Version != 1 {
		return foreignManifest{}, fmt.Errorf("%s is version %d, which this version of OpenSave cannot read", ForeignManifestName, manifest.Version)
	}
	if strings.TrimSpace(manifest.GameKey) == "" || strings.TrimSpace(manifest.GameName) == "" {
		return foreignManifest{}, fmt.Errorf("%s does not name the game it belongs to", ForeignManifestName)
	}
	if len(manifest.Locations) == 0 {
		return foreignManifest{}, fmt.Errorf("%s lists no save locations", ForeignManifestName)
	}

	seen := make(map[string]bool, len(manifest.Locations))
	for _, location := range manifest.Locations {
		archivePath := location.ArchivePath
		if !strings.HasPrefix(archivePath, "location-") ||
			!foreignArchivePathRe.MatchString(archivePath) ||
			strings.Contains(archivePath, "..") {
			return foreignManifest{}, fmt.Errorf("%s has an invalid archive location %q", ForeignManifestName, archivePath)
		}
		if seen[archivePath] {
			return foreignManifest{}, fmt.Errorf("%s names archive location %q more than once", ForeignManifestName, archivePath)
		}
		seen[archivePath] = true
		if !safeForeignRelativePath(location.RelativePath) {
			return foreignManifest{}, fmt.Errorf("%s has an invalid relative path %q", ForeignManifestName, location.RelativePath)
		}
	}
	return manifest, nil
}

// safeForeignRelativePath applies the same rule as the Android manifest
// reader: no absolute path and no parent segment. Backslashes are checked
// too, since a path written on one platform is read on another.
func safeForeignRelativePath(value string) bool {
	if strings.TrimSpace(value) == "" {
		return false
	}
	if strings.HasPrefix(value, "/") || strings.HasPrefix(value, "\\") {
		return false
	}
	for _, segment := range strings.Split(strings.ReplaceAll(value, "\\", "/"), "/") {
		if segment == ".." {
			return false
		}
	}
	return true
}

// unsafeForeignEntry reports whether an archive entry name could land outside
// the directory it is extracted into. path.Clean runs on slash-separated
// names so the answer is the same on every platform; Windows drive letters
// are rejected explicitly because filepath.IsAbs does not see them on Unix.
func unsafeForeignEntry(name string) bool {
	if name == "" {
		return false
	}
	normalized := strings.ReplaceAll(name, "\\", "/")
	if strings.HasPrefix(normalized, "/") {
		return true
	}
	if len(normalized) >= 2 && normalized[1] == ':' && isASCIILetter(normalized[0]) {
		return true
	}
	clean := path.Clean(normalized)
	return clean == ".." || strings.HasPrefix(clean, "../")
}

func isASCIILetter(c byte) bool {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}

// RestoreForeign replaces the local locations named by assignments with the
// matching locations from a foreign archive.
//
// It is deliberately separate from Restore: Restore is for this device's own
// snapshots, where every location in the archive has the same meaning here.
// A foreign archive's locations are the source device's, and only the
// mappings the user confirmed are applied.
//
// A safety snapshot is mandatory and is taken before anything is cleared.
// The ordinary Restore logs a failed safety copy and carries on; here that
// would be the one case where the save being replaced has no copy anywhere.
func (m *Manager) RestoreForeign(gameID, zipPath string, assignments []ForeignAssignment, keep ignore.Rules, safetyComment string) (safety store.Snapshot, err error) {
	defer m.writing(gameID)()

	game, err := m.Store.GetGame(gameID)
	if err != nil {
		return store.Snapshot{}, err
	}
	archive, err := InspectForeignArchive(zipPath)
	if err != nil {
		return store.Snapshot{}, fmt.Errorf("%w: the cloud save manifest or archive cannot be read: %v", ErrForeignArchive, err)
	}
	roots, err := m.Store.GameRootPaths(gameID)
	if err != nil {
		return store.Snapshot{}, err
	}
	targets, err := m.foreignRestoreTargets(game, roots, archive, assignments)
	if err != nil {
		return store.Snapshot{}, err
	}

	// Read the whole archive back before the safety snapshot, so a damaged
	// download is refused while the save on disk is still untouched. The
	// safety copy would otherwise be the only copy of a save that a partial
	// extraction then replaces.
	if err := VerifyArchive(zipPath); err != nil {
		return store.Snapshot{}, fmt.Errorf("%w: %v", ErrForeignArchive, err)
	}

	if m.beforeForeignSafety != nil {
		if err := m.beforeForeignSafety(); err != nil {
			return store.Snapshot{}, fmt.Errorf(
				"could not take a safety snapshot before restoring, so nothing was changed: %w", err)
		}
	}
	safety, err = m.CreateBeforeReplacing(gameID, safetyComment)
	if err != nil {
		return store.Snapshot{}, fmt.Errorf(
			"could not take a safety snapshot before restoring, so nothing was changed: %w", err)
	}

	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return store.Snapshot{}, fmt.Errorf("open save archive: %w", err)
	}
	defer zr.Close()
	byLocation := foreignEntriesByLocation(zr.File, archive.Format, targets)

	var aside string
	if !keep.Empty() {
		aside, err = os.MkdirTemp(m.keepDir(), "keep-*")
		if err != nil {
			return store.Snapshot{}, fmt.Errorf("set aside this device's own files: %w", err)
		}
		defer os.RemoveAll(aside)
	}

	for i, target := range targets {
		sub := ""
		if aside != "" {
			sub = filepath.Join(aside, fmt.Sprint(i))
		}
		if err := m.restoreForeignLocation(target.path, target.archiveLocation, byLocation[target.archiveLocation], keep, sub); err != nil {
			return store.Snapshot{}, err
		}
	}
	return safety, nil
}

type foreignRestoreTarget struct {
	archiveLocation string
	path            string
}

// foreignRestoreTargets validates the whole mapping before the safety
// snapshot is taken. A bad assignment must cost nothing, not half a restore.
func (m *Manager) foreignRestoreTargets(game store.Game, roots map[string]string, archive ForeignArchive, assignments []ForeignAssignment) ([]foreignRestoreTarget, error) {
	if len(assignments) == 0 {
		return nil, fmt.Errorf("%w: choose a restore target for at least one location", ErrForeignAssignment)
	}
	available := make(map[string]bool, len(archive.Locations))
	for _, location := range archive.Locations {
		available[location.ID] = true
	}

	seenArchive := make(map[string]bool, len(assignments))
	seenLocal := make(map[string]bool, len(assignments))
	targets := make([]foreignRestoreTarget, 0, len(assignments))
	for _, assignment := range assignments {
		if !available[assignment.ArchiveLocation] {
			return nil, fmt.Errorf("%w: the archive location %q does not exist in this archive", ErrForeignAssignment, assignment.ArchiveLocation)
		}
		if seenArchive[assignment.ArchiveLocation] {
			return nil, fmt.Errorf("%w: the archive location %q is assigned more than once", ErrForeignAssignment, assignment.ArchiveLocation)
		}
		seenArchive[assignment.ArchiveLocation] = true

		if seenLocal[assignment.LocalRoot] {
			return nil, fmt.Errorf("%w: the local save location %q is assigned more than once", ErrForeignAssignment, assignment.LocalRoot)
		}
		seenLocal[assignment.LocalRoot] = true

		target := game.SavePath
		if assignment.LocalRoot != "" {
			path, ok := roots[assignment.LocalRoot]
			if !ok || strings.TrimSpace(path) == "" {
				return nil, fmt.Errorf("%w: the local save location %q does not exist or is not mapped on this device", ErrForeignAssignment, assignment.LocalRoot)
			}
			target = path
		}
		// A single-file save has no folder to replace, and the archive
		// location may hold a whole folder. The same shape test as UnzipTo:
		// an existing file, or a path whose extension says it is one.
		if foreignTargetLooksLikeFile(target) {
			return nil, fmt.Errorf("%w: single-file save locations cannot be restored across devices", ErrForeignAssignment)
		}
		targets = append(targets, foreignRestoreTarget{
			archiveLocation: assignment.ArchiveLocation,
			path:            target,
		})
	}
	return targets, nil
}

func foreignTargetLooksLikeFile(path string) bool {
	if info, err := os.Stat(path); err == nil {
		return !info.IsDir()
	}
	return len(filepath.Ext(path)) > 1
}

// foreignEntriesByLocation groups the archive's entries by the location they
// belong to. Entries outside every requested location are left out: a
// cross-device restore applies what was mapped and nothing else.
func foreignEntriesByLocation(files []*zip.File, format string, targets []foreignRestoreTarget) map[string][]*zip.File {
	wanted := make(map[string]bool, len(targets))
	for _, target := range targets {
		wanted[target.archiveLocation] = true
	}
	out := make(map[string][]*zip.File, len(wanted))
	prefixDir := strings.TrimSuffix(RootPrefix, "/")
	for _, f := range files {
		if format == ForeignFormatPC {
			if !wanted[ForeignPCRoot] {
				continue
			}
			if f.Name == prefixDir || strings.HasPrefix(f.Name, RootPrefix) {
				continue
			}
			out[ForeignPCRoot] = append(out[ForeignPCRoot], f)
			continue
		}
		for archivePath := range wanted {
			if strings.HasPrefix(f.Name, archivePath+"/") {
				out[archivePath] = append(out[archivePath], f)
				break
			}
		}
	}
	return out
}

// restoreForeignLocation empties one local location and puts the mapped
// archive location's files back. This device's excluded files are set aside
// first and restored afterwards, exactly as RestoreKeeping does for a cloud
// pull: they belong to this machine, not to the save that arrived.
func (m *Manager) restoreForeignLocation(target, archiveLocation string, files []*zip.File, keep ignore.Rules, aside string) (err error) {
	defer delta.InvalidateRoot(target)

	if !keep.Empty() && aside != "" {
		kept, copyErr := copyMatching(target, aside, keep)
		if copyErr != nil {
			return fmt.Errorf("set aside this device's own files in %s: %w", target, copyErr)
		}
		// Put them back whether or not the extraction succeeded: it may
		// already have cleared the folder, and losing them would be worse
		// than the failed restore itself.
		defer func() {
			if rmErr := removeMatching(target, keep); rmErr != nil && err == nil {
				err = fmt.Errorf("remove the other device's excluded files from %s: %w", target, rmErr)
			}
			for _, rel := range kept {
				if copyErr := copyFile(filepath.Join(aside, rel), filepath.Join(target, rel)); copyErr != nil && err == nil {
					err = fmt.Errorf("put back %s: %w", rel, copyErr)
				}
			}
		}()
	}

	if err := clearSavePathGuarded(target); err != nil {
		return fmt.Errorf("clear %s: %w", target, err)
	}
	for _, f := range files {
		rel := f.Name
		if archiveLocation != ForeignPCRoot {
			rel = strings.TrimPrefix(f.Name, archiveLocation+"/")
		}
		if rel == "" {
			// The location's own folder marker; the folder must exist even
			// when the location held no files.
			if err := os.MkdirAll(target, 0o777); err != nil {
				return err
			}
			continue
		}
		if err := extractEntryAs(f, target, rel); err != nil {
			return err
		}
	}
	return nil
}
