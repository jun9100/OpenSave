package daemon

import (
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

func setNativeScopeGone(t *testing.T, service *nativeGameSaveTestServer, kind, gameID string, gone cloud.GameSaveScopeGoneError) {
	t.Helper()
	service.mu.Lock()
	defer service.mu.Unlock()
	service.goneScopes[nativeGameSaveScopeKey(kind, gameID)] = gone
}

func setNativeUploadGone(t *testing.T, service *nativeGameSaveTestServer, kind, gameID string, gone cloud.GameSaveScopeGoneError) {
	t.Helper()
	service.mu.Lock()
	defer service.mu.Unlock()
	service.goneUploads[nativeGameSaveScopeKey(kind, gameID)] = gone
}

func nativeServerCounts(service *nativeGameSaveTestServer) (filePuts, scopePuts, scopeGets, requests int) {
	service.mu.Lock()
	defer service.mu.Unlock()
	return service.filePuts, service.scopePuts, service.scopeGets, len(service.requests)
}

func nativeRequestsSince(service *nativeGameSaveTestServer, from int) []string {
	service.mu.Lock()
	defer service.mu.Unlock()
	if from > len(service.requests) {
		return nil
	}
	return append([]string(nil), service.requests[from:]...)
}

func containsRequestPath(requests []string, method, part string) bool {
	for _, request := range requests {
		if strings.HasPrefix(request, method+" ") && strings.Contains(request, part) {
			return true
		}
	}
	return false
}

func countLogMessages(d *Daemon, part string) int {
	count := 0
	for _, entry := range d.Log.History() {
		if strings.Contains(entry.Message, part) {
			count++
		}
	}
	return count
}

func TestNativeGameSaveDeletedStopsWithoutRecreatingScope(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	base := desktop.play("base")
	now := time.Date(2026, 11, 9, 12, 0, 0, 0, time.UTC)
	setNativeScopeGone(t, service, "switch", cloudGame, cloud.GameSaveScopeGoneError{
		Code: "scope_deleted", DeletedAt: now,
	})

	beforeHead, found, err := desktop.d.Store.GetCloudHead(cloudGame)
	if err != nil || !found || beforeHead.NativeHead == "" {
		t.Fatalf("initial cloud head = (%+v, %v, %v)", beforeHead, found, err)
	}
	beforeSnapshots, err := desktop.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	beforeSave, err := os.ReadFile(filepath.Join(desktop.save, "user1.dat"))
	if err != nil {
		t.Fatal(err)
	}
	filePuts, scopePuts, _, requests := nativeServerCounts(service)

	handled, err := desktop.d.runGameSaveScopeUpload(base.ZipPath, cloudSnapshotName(cloudGame, "main", base.ID))
	if !handled {
		t.Fatal("deleted scope fell back to the legacy protocol")
	}
	gone, ok := cloud.IsGameSaveScopeGone(err)
	if !ok || gone.Code != "scope_deleted" {
		t.Fatalf("upload error = %T %v, want scope_deleted", err, err)
	}

	afterFilePuts, afterScopePuts, _, afterRequests := nativeServerCounts(service)
	if afterFilePuts != filePuts {
		t.Fatalf("archive PUTs = %d, want no new upload (%d)", afterFilePuts-filePuts, filePuts)
	}
	if afterScopePuts != scopePuts {
		t.Fatalf("scope PUTs = %d, want no new scope write (%d)", afterScopePuts-scopePuts, scopePuts)
	}
	if containsRequestPath(nativeRequestsSince(service, requests), http.MethodPut, "/snapshots/") {
		t.Fatal("a tombstoned scope received a snapshot PUT")
	}
	if afterRequests != requests+1 || !containsRequestPath(nativeRequestsSince(service, requests), http.MethodGet, "/scopes/switch/"+cloudGame) {
		t.Fatalf("requests after 410 = %d, want exactly the scope GET", afterRequests-requests)
	}
	head, found, err := desktop.d.Store.GetCloudHead(cloudGame)
	if err != nil || !found || head.NativeHead != "" {
		t.Fatalf("native head after 410 = (%+v, %v, %v), want cleared", head, found, err)
	}
	afterSnapshots, err := desktop.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(afterSnapshots) != len(beforeSnapshots) {
		t.Fatalf("local snapshots = %d, want unchanged %d", len(afterSnapshots), len(beforeSnapshots))
	}
	afterSave, err := os.ReadFile(filepath.Join(desktop.save, "user1.dat"))
	if err != nil {
		t.Fatal(err)
	}
	if string(afterSave) != string(beforeSave) {
		t.Fatalf("local save changed from %q to %q", beforeSave, afterSave)
	}
	record, found, err := desktop.d.Store.GetCloudScopeGone(cloudGame)
	if err != nil || !found {
		t.Fatalf("gone record = (%+v, %v, %v)", record, found, err)
	}
	if record.Code != "scope_deleted" || record.GoneAt != now.Format(time.RFC3339) || record.Notice != "pending" {
		t.Fatalf("gone record = %+v", record)
	}
	if got := countLogMessages(desktop.d, "was deleted on the game-save server"); got != 1 {
		t.Fatalf("deleted info logs = %d, want 1", got)
	}

	// A recorded scope is skipped before any network call, on every native path.
	_, _, _, requests = nativeServerCounts(service)
	if _, err := desktop.d.runGameSaveScopeUpload(base.ZipPath, cloudSnapshotName(cloudGame, "main", base.ID)); !errors.Is(err, ErrGameSaveScopeSyncStopped) {
		t.Fatalf("second upload error = %v, want ErrGameSaveScopeSyncStopped", err)
	}
	desktop.d.CheckCloud()
	if err := desktop.d.PublishCurrentGameSaveHead(cloudGame); !errors.Is(err, ErrGameSaveScopeSyncStopped) {
		t.Fatalf("publish error = %v, want ErrGameSaveScopeSyncStopped", err)
	}
	_, _, _, afterSkipRequests := nativeServerCounts(service)
	if afterSkipRequests != requests {
		t.Fatalf("skip paths made %d requests, want 0", afterSkipRequests-requests)
	}

	// Status comes entirely from the local record.
	_, _, _, requests = nativeServerCounts(service)
	status, err := desktop.d.GameSaveStatus(cloudGame)
	if err != nil {
		t.Fatal(err)
	}
	_, _, _, afterStatusRequests := nativeServerCounts(service)
	if afterStatusRequests != requests {
		t.Fatalf("status made %d requests, want 0", afterStatusRequests-requests)
	}
	if status.State != gameSaveStatusScopeDeleted || status.Gone == nil || status.Gone.At != now.Format(time.RFC3339) {
		t.Fatalf("status = %+v, gone = %+v", status, status.Gone)
	}

	// Choosing "off" survives another observation of the same tombstone.
	if err := desktop.d.Store.SetCloudScopeGoneNotice(cloudGame, "off"); err != nil {
		t.Fatal(err)
	}
	desktop.d.noteGameSaveScopeGone(mustGame(t, desktop.d, cloudGame), gone, false)
	record, found, err = desktop.d.Store.GetCloudScopeGone(cloudGame)
	if err != nil || !found || record.Notice != "off" {
		t.Fatalf("notice after second 410 = (%+v, %v, %v), want off", record, found, err)
	}
	if got := countLogMessages(desktop.d, "was deleted on the game-save server"); got != 1 {
		t.Fatalf("deleted info logs after repeat = %d, want 1", got)
	}
}

func mustGame(t *testing.T, d *Daemon, gameID string) store.Game {
	t.Helper()
	game, err := d.Store.GetGame(gameID)
	if err != nil {
		t.Fatal(err)
	}
	return game
}

func TestNativeGameSaveRetiredRecordsSuccessorWithoutRestoreOrUpload(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	base := desktop.play("base")
	successorPath := t.TempDir()
	if err := desktop.d.Store.CreateGame(store.Game{
		ID: "forza-horizon-5", Name: "Forza Horizon 5", SavePath: successorPath,
		ActiveBranch: "main", AutoSync: true, MaxSnapshots: 20, SaveType: "switch",
	}); err != nil {
		t.Fatal(err)
	}
	setNativeScopeGone(t, service, "switch", cloudGame, cloud.GameSaveScopeGoneError{
		Code: "scope_retired", SuccessorType: "switch", SuccessorGameID: "forza-horizon-5",
	})
	beforeSnapshots, err := desktop.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	beforeSave, err := os.ReadFile(filepath.Join(desktop.save, "user1.dat"))
	if err != nil {
		t.Fatal(err)
	}
	filePuts, scopePuts, _, _ := nativeServerCounts(service)

	handled, err := desktop.d.runGameSaveScopeUpload(base.ZipPath, cloudSnapshotName(cloudGame, "main", base.ID))
	if !handled {
		t.Fatal("retired scope fell back to the legacy protocol")
	}
	if gone, ok := cloud.IsGameSaveScopeGone(err); !ok || gone.Code != "scope_retired" {
		t.Fatalf("upload error = %T %v, want scope_retired", err, err)
	}
	record, found, err := desktop.d.Store.GetCloudScopeGone(cloudGame)
	if err != nil || !found {
		t.Fatalf("gone record = (%+v, %v, %v)", record, found, err)
	}
	if record.Code != "scope_retired" || record.SuccessorType != "switch" ||
		record.SuccessorGameID != "forza-horizon-5" || record.SuccessorName != "Forza Horizon 5" {
		t.Fatalf("retired record = %+v", record)
	}
	afterFilePuts, afterScopePuts, _, _ := nativeServerCounts(service)
	if afterFilePuts != filePuts || afterScopePuts != scopePuts {
		t.Fatalf("retired scope wrote: file PUTs %d, scope PUTs %d", afterFilePuts-filePuts, afterScopePuts-scopePuts)
	}
	afterSnapshots, err := desktop.d.Store.ListSnapshots(cloudGame, "main")
	if err != nil {
		t.Fatal(err)
	}
	if len(afterSnapshots) != len(beforeSnapshots) {
		t.Fatalf("retired scope created a local snapshot: %d -> %d", len(beforeSnapshots), len(afterSnapshots))
	}
	afterSave, err := os.ReadFile(filepath.Join(desktop.save, "user1.dat"))
	if err != nil {
		t.Fatal(err)
	}
	if string(afterSave) != string(beforeSave) {
		t.Fatalf("retired scope restored files: %q -> %q", beforeSave, afterSave)
	}
	if got := countLogMessages(desktop.d, "was merged into"); got != 1 {
		t.Fatalf("retired info logs = %d, want 1", got)
	}
	status, err := desktop.d.GameSaveStatus(cloudGame)
	if err != nil {
		t.Fatal(err)
	}
	if status.State != gameSaveStatusScopeRetired || status.Gone == nil ||
		status.Gone.SuccessorLocalGameID != "forza-horizon-5" ||
		status.Gone.SuccessorName != "Forza Horizon 5" {
		t.Fatalf("retired status = %+v, gone = %+v", status, status.Gone)
	}
}

func TestNativeGameSaveGoneOnArchiveUploadStopsBeforeSnapshotWrite(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	desktop.play("base")
	setNativeUploadGone(t, service, "switch", cloudGame, cloud.GameSaveScopeGoneError{
		Code: "scope_deleted", DeletedAt: time.Now().UTC(),
	})
	filePuts, scopePuts, _, requests := nativeServerCounts(service)

	desktop.play("after deletion")

	if _, found, err := desktop.d.Store.GetCloudScopeGone(cloudGame); err != nil || !found {
		t.Fatalf("gone record = (%v, %v)", found, err)
	}
	head, found, err := desktop.d.Store.GetCloudHead(cloudGame)
	if err != nil || !found || head.NativeHead != "" {
		t.Fatalf("native head after upload 410 = (%+v, %v, %v)", head, found, err)
	}
	afterFilePuts, afterScopePuts, _, _ := nativeServerCounts(service)
	if afterFilePuts != filePuts+1 {
		t.Fatalf("archive PUTs = %d, want one refused attempt", afterFilePuts-filePuts)
	}
	if afterScopePuts != scopePuts {
		t.Fatalf("snapshot PUTs = %d, want none after archive 410", afterScopePuts-scopePuts)
	}
	if containsRequestPath(nativeRequestsSince(service, requests), http.MethodPut, "/snapshots/") {
		t.Fatal("archive-stage 410 was followed by a snapshot PUT")
	}
}

func TestNativeCheckContinuesAfterGoneScope(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	desktop.play("base")
	if err := desktop.d.Store.CreateGame(store.Game{
		ID: "second-game", Name: "Second Game", SavePath: t.TempDir(),
		ActiveBranch: "main", AutoSync: true, MaxSnapshots: 20, SaveType: "switch",
	}); err != nil {
		t.Fatal(err)
	}
	setNativeScopeGone(t, service, "switch", cloudGame, cloud.GameSaveScopeGoneError{
		Code: "scope_deleted", DeletedAt: time.Now().UTC(),
	})
	_, _, beforeGets, requests := nativeServerCounts(service)

	desktop.d.CheckCloud()

	_, _, afterGets, _ := nativeServerCounts(service)
	if afterGets != beforeGets+2 {
		t.Fatalf("scope GETs = %d, want both games checked (%d)", afterGets-beforeGets, beforeGets)
	}
	if !containsRequestPath(nativeRequestsSince(service, requests), http.MethodGet, "/scopes/switch/second-game") {
		t.Fatalf("second game was not checked after the first was gone: %v", nativeRequestsSince(service, requests))
	}
	if _, found, err := desktop.d.Store.GetCloudScopeGone(cloudGame); err != nil || !found {
		t.Fatalf("gone record for first game = (%v, %v)", found, err)
	}
}

func TestNativeGameSaveUploadRefusalDoesNotEnterRetryQueue(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	base := desktop.play("base")
	name := cloudSnapshotName(cloudGame, "main", base.ID)
	key := cloud.RetryKey("switch", name)
	if err := desktop.d.Store.NoteCloudUploadFailed(key, cloudGame, name, base.ZipPath, time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}

	setNativeScopeGone(t, service, "switch", cloudGame, cloud.GameSaveScopeGoneError{Code: "scope_deleted"})
	desktop.d.uploads.Add()
	desktop.d.runCloudUpload(base.ZipPath, name, key, desktop.d.Log)
	if pending, _ := desktop.d.Store.CloudRetries(); len(pending) != 0 {
		t.Fatalf("gone upload remained queued: %+v", pending)
	}
	if _, found, _ := desktop.d.Store.GetCloudScopeGone(cloudGame); !found {
		t.Fatal("gone upload did not record the server tombstone")
	}
}

func TestNativeGameSaveUnknown410DoesNotEnterRetryQueue(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	base := desktop.play("base")
	name := cloudSnapshotName(cloudGame, "main", base.ID)
	key := cloud.RetryKey("switch", name)
	if err := desktop.d.Store.NoteCloudUploadFailed(key, cloudGame, name, base.ZipPath, time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}
	setNativeScopeGone(t, service, "switch", cloudGame, cloud.GameSaveScopeGoneError{Code: "scope_future"})
	_, _, _, requests := nativeServerCounts(service)

	desktop.d.uploads.Add()
	desktop.d.runCloudUpload(base.ZipPath, name, key, desktop.d.Log)

	if pending, _ := desktop.d.Store.CloudRetries(); len(pending) != 0 {
		t.Fatalf("unknown 410 remained queued: %+v", pending)
	}
	if _, found, _ := desktop.d.Store.GetCloudScopeGone(cloudGame); found {
		t.Fatal("unknown 410 was recorded as a known tombstone")
	}
	if !containsRequestPath(nativeRequestsSince(service, requests), http.MethodGet, "/scopes/switch/"+cloudGame) {
		t.Fatal("unknown 410 was not read from the service")
	}
	if countLogMessages(desktop.d, "was refused") == 0 {
		t.Fatal("unknown 410 was not logged as an error")
	}
}

func TestNativeGameSaveStatusReportsLockedVersions(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	desktop.play("base")
	service.mu.Lock()
	service.locked = true
	service.mu.Unlock()

	status, err := desktop.d.GameSaveStatus(cloudGame)
	if err != nil {
		t.Fatal(err)
	}
	if status.CloudSnapshot == nil || !status.CloudSnapshot.Locked || len(status.Snapshots) == 0 || !status.Snapshots[0].Locked {
		t.Fatalf("locked status = %+v, cloudSnapshot = %+v", status, status.CloudSnapshot)
	}
}

func TestRetryFailedUploadsDropsGoneGameWithoutRequest(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	base := desktop.play("base")
	name := cloudSnapshotName(cloudGame, "main", base.ID)
	key := cloud.RetryKey("switch", name)
	if err := desktop.d.Store.NoteCloudUploadFailed(key, cloudGame, name, base.ZipPath, time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}
	setNativeScopeGone(t, service, "switch", cloudGame, cloud.GameSaveScopeGoneError{Code: "scope_deleted"})
	if err := desktop.d.Store.PutCloudScopeGone(store.CloudScopeGone{
		GameID: cloudGame, Code: "scope_deleted", GoneAt: time.Now().UTC().Format(time.RFC3339),
		Notice: "pending", RecordedAt: time.Now().UTC().Format(time.RFC3339),
	}); err != nil {
		t.Fatal(err)
	}
	_, _, _, requests := nativeServerCounts(service)

	desktop.d.retryFailedUploads(nil)

	if pending, _ := desktop.d.Store.CloudRetries(); len(pending) != 0 {
		t.Fatalf("gone retry remained queued: %+v", pending)
	}
	if after := nativeRequestsSince(service, requests); len(after) != 0 {
		t.Fatalf("gone retry reached the service: %v", after)
	}
}

func TestNativeGameSaveStatusRecordsGoneFromRead(t *testing.T) {
	service := newNativeGameSaveTestServer(t)
	desktop := newNativeCloudDevice(t, "Desktop", "desktop-node", service.url())
	service.mu.Lock()
	service.scopes[nativeGameSaveScopeKey("switch", cloudGame)] = &nativeGameSaveTestScope{
		snapshots: map[string]cloud.GameSaveScopeSnapshot{},
		conflicts: map[string]cloud.GameSaveScopeSnapshot{},
	}
	service.mu.Unlock()
	setNativeScopeGone(t, service, "switch", cloudGame, cloud.GameSaveScopeGoneError{
		Code: "scope_deleted", DeletedAt: time.Date(2026, 11, 9, 12, 0, 0, 0, time.UTC),
	})

	status, err := desktop.d.GameSaveStatus(cloudGame)
	if err != nil {
		t.Fatal(err)
	}
	if status.State != gameSaveStatusScopeDeleted || status.Gone == nil || status.Gone.Code != "scope_deleted" {
		t.Fatalf("status = %+v, gone = %+v", status, status.Gone)
	}
	record, found, err := desktop.d.Store.GetCloudScopeGone(cloudGame)
	if err != nil || !found || record.Code != "scope_deleted" {
		t.Fatalf("record = (%+v, %v, %v)", record, found, err)
	}
	if got := countLogMessages(desktop.d, "was deleted on the game-save server"); got != 1 {
		t.Fatalf("deleted info logs = %d, want 1", got)
	}
}
