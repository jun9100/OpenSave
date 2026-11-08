package daemon

import (
	"strings"
	"testing"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

func TestNativeGameSaveIdentityReportsWhatTheGameIs(t *testing.T) {
	cases := []struct {
		game store.Game
		want cloud.GameSaveScopeIdentity
	}{
		{store.Game{ID: "half-life", Name: "Half-Life", AppID: "70", SaveType: "pc"},
			cloud.GameSaveScopeIdentity{Name: "Half-Life", SteamAppID: "70"}},
		{store.Game{ID: "switch-0100000000010000", Name: "Super Mario Odyssey", SaveType: "switch"},
			cloud.GameSaveScopeIdentity{Name: "Super Mario Odyssey", TitleID: "0100000000010000"}},
		// A non-numeric app id and a Switch-looking id outside the switch type are not facts.
		{store.Game{ID: "switch-0100000000010000", Name: "Port", AppID: "not-steam", SaveType: "pc"},
			cloud.GameSaveScopeIdentity{Name: "Port"}},
		{store.Game{ID: "x", Name: "bad\nname", SaveType: "pc"}, cloud.GameSaveScopeIdentity{}},
		{store.Game{ID: "y", Name: strings.Repeat("长", 121), SaveType: "pc"}, cloud.GameSaveScopeIdentity{}},
	}
	for _, c := range cases {
		if got := nativeGameSaveIdentity(c.game); got != c.want {
			t.Errorf("nativeGameSaveIdentity(%+v) = %+v, want %+v", c.game, got, c.want)
		}
	}
}
