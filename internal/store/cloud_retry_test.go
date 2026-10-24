package store

import "testing"

func TestCloudRetriesKeepMatchingNamesInSeparateSaveTypes(t *testing.T) {
	s := openTestStore(t)
	for _, game := range []Game{
		{ID: "pc-game", Name: "PC Game", SavePath: t.TempDir(), SaveType: "pc"},
		{ID: "switch-game", Name: "Switch Game", SavePath: t.TempDir(), SaveType: "switch"},
	} {
		if err := s.CreateGame(game); err != nil {
			t.Fatal(err)
		}
	}

	const name = "same-game__main__snap_1.zip"
	if err := s.NoteCloudUploadFailed("pc\x00"+name, "pc-game", name, "/tmp/pc.zip", 1); err != nil {
		t.Fatal(err)
	}
	if err := s.NoteCloudUploadFailed("switch\x00"+name, "switch-game", name, "/tmp/switch.zip", 2); err != nil {
		t.Fatal(err)
	}
	queued, err := s.CloudRetries()
	if err != nil {
		t.Fatal(err)
	}
	if len(queued) != 2 || queued[0].RemoteKey == queued[1].RemoteKey {
		t.Fatalf("queued = %+v, want distinct PC and Switch retries", queued)
	}
	if err := s.ForgetCloudRetry("pc\x00" + name); err != nil {
		t.Fatal(err)
	}
	queued, err = s.CloudRetries()
	if err != nil || len(queued) != 1 || queued[0].GameID != "switch-game" {
		t.Fatalf("after PC completion queued = %+v, %v; want only Switch", queued, err)
	}
}
