package store

import (
	"path/filepath"
	"testing"
)

func TestCloudScopeGoneRoundTrip(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "opensave.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	record := CloudScopeGone{
		GameID:          "hollow-knight",
		Code:            "scope_retired",
		GoneAt:          "2026-11-09T12:00:00Z",
		SuccessorType:   "switch",
		SuccessorGameID: "forza-horizon-5",
		SuccessorName:   "Forza Horizon 5",
		Notice:          "pending",
		RecordedAt:      "2026-11-09T12:01:00Z",
	}
	if err := s.PutCloudScopeGone(record); err != nil {
		t.Fatal(err)
	}
	got, found, err := s.GetCloudScopeGone(record.GameID)
	if err != nil || !found {
		t.Fatalf("GetCloudScopeGone = (%+v, %v, %v)", got, found, err)
	}
	if got != record {
		t.Fatalf("record = %+v, want %+v", got, record)
	}
	if err := s.SetCloudScopeGoneNotice(record.GameID, "off"); err != nil {
		t.Fatal(err)
	}
	got, found, err = s.GetCloudScopeGone(record.GameID)
	if err != nil || !found || got.Notice != "off" {
		t.Fatalf("notice = (%+v, %v, %v)", got, found, err)
	}
	records, err := s.ListCloudScopeGone()
	if err != nil || len(records) != 1 || records[0].GameID != record.GameID {
		t.Fatalf("ListCloudScopeGone = (%+v, %v)", records, err)
	}
	if err := s.DeleteCloudScopeGone(record.GameID); err != nil {
		t.Fatal(err)
	}
	if _, found, err := s.GetCloudScopeGone(record.GameID); err != nil || found {
		t.Fatalf("record survived delete: found=%v err=%v", found, err)
	}
}

func TestSetCloudScopeGoneNoticeMissingIsNotFound(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "opensave.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.SetCloudScopeGoneNotice("missing", "off"); err != ErrNotFound {
		t.Fatalf("missing notice error = %v, want ErrNotFound", err)
	}
}
