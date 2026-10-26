package cloud

import (
	"archive/zip"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// A head is one device saying, in the cloud, which of its uploaded snapshots
// is the save it has now.
//
// The mirror used to be write-only: every device uploaded, none looked, so a
// save made on a machine that was then switched off reached the others only
// if somebody opened the cloud screen and knew to look. Snapshot names cannot
// carry what reading needs. They name no device, and every snapshot is
// uploaded, including the copy a device keeps of a save it is about to
// replace, so "the newest file wins" would hand another device an old save
// with a new date on it. A head is the device's own statement of where it is,
// and of the line of saves that led there — which is what tells another
// device whether taking it would carry on from what it has or throw
// something away.
//
// Stored as a small zip holding head.json. Every provider already lists,
// uploads and downloads zips, and a name with two parts rather than three is
// skipped by the snapshot parser in every version that has one — so older
// versions sharing the folder ignore these files instead of mistaking them
// for backups.
type Head struct {
	Version    int    `json:"version"`
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	// GameID is the uploading device's id for the game, which may differ
	// from this device's for a game linked under another name.
	GameID string `json:"gameId"`
	Branch string `json:"branch"`
	// Snapshot is the save the device has now, and File is that snapshot's
	// name in the cloud.
	Snapshot string `json:"snapshot"`
	File     string `json:"file"`
	// Chain is the device's saves since it last took one from elsewhere,
	// oldest first and ending at Snapshot. Another device whose own save
	// appears here knows that taking Snapshot continues from it.
	Chain []string `json:"chain"`
	// SavedAt remains a display-friendly timestamp for older clients. New
	// writers set it to the newest modification time inside the saved archive.
	SavedAt string `json:"savedAt"`
	// ContentModifiedAt explicitly identifies SavedAt as a time carried by the
	// save data, rather than the later moment an old copy happened to be backed
	// up. Readers only auto-apply heads with this field.
	ContentModifiedAt string `json:"contentModifiedAt,omitempty"`
}

// HeadVersion is written into every head; readers skip versions they do not
// know rather than guess at them.
const HeadVersion = 1

// MaxHeadChain bounds Chain. A device another has not heard from for longer
// than this many saves is asked about rather than followed automatically,
// which is the cautious way to be wrong.
const MaxHeadChain = 64

const headEntry = "head.json"

const gameSaveDeviceSuffix = "--dev-"

// headNameRe matches <gameId>__head-<device>-<unix ms>.zip. The device part
// is DeviceKey's alphabet, so the dashes around it are unambiguous.
var headNameRe = regexp.MustCompile(`^(.+)__head-([a-z0-9]+)-(\d+)\.zip$`)

// DeviceKey is a device id reduced to what is safe in a file name.
func DeviceKey(deviceID string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(deviceID) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// GameSaveSnapshotID turns a device-local snapshot id into an identifier that
// is safe to use as a game-save scope snapshot id. Local snapshots retain
// their historical snap_<millis> ids, while the server-facing suffix prevents
// two devices creating different archives during the same millisecond from
// colliding.
func GameSaveSnapshotID(snapshotID, deviceID string) string {
	key := DeviceKey(deviceID)
	if snapshotID == "" || key == "" {
		return ""
	}
	return snapshotID + gameSaveDeviceSuffix + key
}

// GameSaveArchiveName names a native game-save archive. It intentionally
// keeps the conventional three-part archive layout so legacy readers can
// still recognize it as a snapshot, while the final component remains unique
// per source device.
func GameSaveArchiveName(gameID, branch, snapshotID, deviceID string) string {
	remoteSnapshotID := GameSaveSnapshotID(snapshotID, deviceID)
	if gameID == "" || branch == "" || remoteSnapshotID == "" {
		return ""
	}
	return fmt.Sprintf("%s__%s__%s.zip", gameID, branch, remoteSnapshotID)
}

// ParseGameSaveArchiveName recognizes the device-unique archive format. The
// returned snapshot id is the server-facing id, not the local snap_<millis>
// id, and is therefore suitable for a scope parent or head comparison.
func ParseGameSaveArchiveName(name string) (gameID, branch, snapshotID string, ok bool) {
	name = strings.TrimSuffix(name, ".zip")
	parts := strings.SplitN(name, "__", 3)
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" ||
		!strings.Contains(parts[2], gameSaveDeviceSuffix) {
		return "", "", "", false
	}
	return parts[0], parts[1], parts[2], true
}

// HeadFileName names a head written at a given moment. The time is in the
// name because Google Drive does not replace a file by name — it adds a
// second one — so each head is a new file, the newest per device is the one
// that counts, and older ones are removed afterwards.
func HeadFileName(gameID, deviceID string, at time.Time) string {
	return fmt.Sprintf("%s__head-%s-%d.zip", gameID, DeviceKey(deviceID), at.UnixMilli())
}

// ParseHeadFileName reverses HeadFileName.
func ParseHeadFileName(name string) (gameID, deviceKey string, atMs int64, ok bool) {
	m := headNameRe.FindStringSubmatch(name)
	if m == nil {
		return "", "", 0, false
	}
	ms, err := strconv.ParseInt(m[3], 10, 64)
	if err != nil {
		return "", "", 0, false
	}
	return m[1], m[2], ms, true
}

// WriteHeadFile writes h as a head archive at path.
func WriteHeadFile(h Head, path string) error {
	body, err := json.MarshalIndent(h, "", "  ")
	if err != nil {
		return err
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	zw := zip.NewWriter(f)
	w, err := zw.Create(headEntry)
	if err == nil {
		_, err = w.Write(body)
	}
	if cerr := zw.Close(); err == nil {
		err = cerr
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

// ReadHeadFile reads a head archive. A head is a few hundred bytes; anything
// much larger is not one, and is refused rather than read into memory.
func ReadHeadFile(path string) (Head, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return Head{}, err
	}
	defer zr.Close()
	for _, f := range zr.File {
		if f.Name != headEntry {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return Head{}, err
		}
		body, err := io.ReadAll(io.LimitReader(rc, 64<<10))
		rc.Close()
		if err != nil {
			return Head{}, err
		}
		var h Head
		if err := json.Unmarshal(body, &h); err != nil {
			return Head{}, fmt.Errorf("read head: %w", err)
		}
		return h, nil
	}
	return Head{}, fmt.Errorf("read head: no %s inside", headEntry)
}

// PublishHead uploads h as the newest head for its game from its device.
func (s *Service) PublishHead(h Head, at time.Time) (string, error) {
	dir, err := os.MkdirTemp("", "opensave-head-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(dir)
	tmp := filepath.Join(dir, headEntry+".zip")
	if err := WriteHeadFile(h, tmp); err != nil {
		return "", err
	}
	name := HeadFileName(h.GameID, h.DeviceID, at)
	if err := s.upload(tmp, name, false); err != nil {
		return "", err
	}
	return name, nil
}

// FetchHead downloads and reads one head file.
func (s *Service) FetchHead(name string) (Head, error) {
	return s.FetchHeadFile(CloudFile{Name: name})
}

// FetchHeadFile downloads and reads one listed head file. Keeping the
// CloudFile type reaches the correct native namespace when names coincide.
func (s *Service) FetchHeadFile(file CloudFile) (Head, error) {
	dir, err := os.MkdirTemp("", "opensave-head-*")
	if err != nil {
		return Head{}, err
	}
	defer os.RemoveAll(dir)
	tmp := filepath.Join(dir, "head.zip")
	if err := s.DownloadFile(file, tmp); err != nil {
		return Head{}, err
	}
	return ReadHeadFile(tmp)
}
