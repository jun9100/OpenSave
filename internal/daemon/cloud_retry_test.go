package daemon

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
)

// A cloud copy that fails to go up — the network down, a name that will not
// resolve, a connection that stalls — is sent again once the cloud can be
// reached, instead of being left out of the backup for good. It used to be
// logged and dropped: only "Upload local snapshots", pressed by hand, ever
// sent it.
//
// The cloud here is a local folder. It starts as a file, so every upload into
// it fails, and becomes a folder: the network coming back.
func TestAFailedCloudUploadIsSentAgainWhenTheCloudIsBack(t *testing.T) {
	cloudDir := filepath.Join(t.TempDir(), "cloud")
	if err := os.WriteFile(cloudDir, []byte("not a folder yet"), 0o666); err != nil {
		t.Fatal(err)
	}
	dev := newCloudDevice(t, "Desktop", cloudDir)

	snap := dev.play("progress made while offline")
	name := cloudSnapshotName(cloudGame, "main", snap.ID)
	pending, err := dev.d.Store.CloudRetries()
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 1 || pending[0].RemoteName != name {
		t.Fatalf("the failed upload was not kept to try again: %+v", pending)
	}

	// The cloud comes back.
	if err := os.Remove(cloudDir); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(cloudDir, 0o777); err != nil {
		t.Fatal(err)
	}
	dev.check()

	if _, err := os.Stat(filepath.Join(cloudDir, name)); err != nil {
		t.Fatalf("the snapshot never reached the cloud: %v", err)
	}
	if pending, _ := dev.d.Store.CloudRetries(); len(pending) != 0 {
		t.Errorf("still waiting to send what has been sent: %+v", pending)
	}
}

// Drive-like providers put an opaque provider file id in CloudFile.ID. It is
// not a save type, so an already-uploaded file must still settle the matching
// retry rather than being uploaded on every cloud check.
func TestAFlatProviderFileIDDoesNotDefeatRetryDeduplication(t *testing.T) {
	dev := newCloudDevice(t, "Desktop", t.TempDir())
	const name = "hollow-knight__main__snap_9.zip"
	if err := dev.d.Store.NoteCloudUploadFailed(cloud.RetryKey("pc", name), cloudGame, name, "missing-is-fine-when-listed.zip", time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}

	dev.d.retryFailedUploads([]cloud.CloudFile{{ID: "provider-file-id", Name: name}})
	pending, err := dev.d.Store.CloudRetries()
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("listed flat-provider retry was not forgotten: %+v", pending)
	}
}

// One that no longer exists here — pruned while it waited — is forgotten, not
// tried for ever.
func TestAFailedUploadOfASnapshotSinceRemovedIsForgotten(t *testing.T) {
	cloudDir := filepath.Join(t.TempDir(), "cloud")
	if err := os.WriteFile(cloudDir, []byte("not a folder yet"), 0o666); err != nil {
		t.Fatal(err)
	}
	dev := newCloudDevice(t, "Desktop", cloudDir)
	snap := dev.play("soon gone")
	if _, err := dev.d.Snapshots.DeleteSnapshot(cloudGame, snap.ID); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(cloudDir); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(cloudDir, 0o777); err != nil {
		t.Fatal(err)
	}
	dev.check()
	if pending, _ := dev.d.Store.CloudRetries(); len(pending) != 0 {
		t.Errorf("a snapshot that is gone is still waiting to be sent: %+v", pending)
	}
	if entries, _ := os.ReadDir(cloudDir); len(entries) != 0 {
		t.Errorf("something was sent for a snapshot that is gone: %v", entries)
	}
}
