package daemon

import (
	"errors"
	"fmt"
	"regexp"
	"sort"
	"time"

	"github.com/opensave/opensave/internal/cloud"
	"github.com/opensave/opensave/internal/store"
)

const (
	gameSaveGroupOK         = "ok"
	gameSaveGroupNotGrouped = "not_grouped"
)

var (
	// ErrCrossDeviceSameScope rejects a version listing for this device's own
	// scope: it is not a different device and must not be presented as one.
	ErrCrossDeviceSameScope = errors.New("memberGameId names this device's own game-save scope")
	// ErrInvalidGameSaveMemberID rejects values that cannot be a scope id.
	ErrInvalidGameSaveMemberID = errors.New("memberGameId must be 1-128 characters of A-Z, a-z, 0-9, '.', '_' or '-'")
)

var gameSaveMemberIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

// GameSaveGroupMember is one other device's scope inside a game group. Every
// field comes from the service's grouping index; nothing here is inferred
// from local files or from a head's timestamp.
type GameSaveGroupMember struct {
	Type          string    `json:"type"`
	GameID        string    `json:"gameId"`
	Name          string    `json:"name"`
	Link          string    `json:"link"`
	DeviceName    string    `json:"deviceName"`
	Platform      string    `json:"platform"`
	LastVersionAt time.Time `json:"lastVersionAt,omitzero"`
	SnapshotCount int       `json:"snapshotCount"`
	ConflictCount int       `json:"conflictCount"`
	HeadID        string    `json:"headId"`
	HeadArchive   string    `json:"headArchive"`
}

// GameSaveGroup is the read-only answer for one local game. Unsupported,
// offline, and unconfigured services are reported in-band so the UI can
// distinguish them from a missing game without a failed request.
type GameSaveGroup struct {
	Configured bool                  `json:"configured"`
	Supported  bool                  `json:"supported"`
	State      string                `json:"state"`
	Detail     string                `json:"detail"`
	GameKey    string                `json:"gameKey"`
	GameName   string                `json:"gameName"`
	SaveType   string                `json:"saveType"`
	Members    []GameSaveGroupMember `json:"members"`
}

// GameSaveMemberVersion is one restorable version in another device's scope.
// The archive contents are deliberately not inspected here.
type GameSaveMemberVersion struct {
	ID         string    `json:"id"`
	Archive    string    `json:"archive"`
	CreatedAt  time.Time `json:"createdAt"`
	ReceivedAt time.Time `json:"receivedAt"`
	DeviceName string    `json:"deviceName"`
	Platform   string    `json:"platform"`
	IsHead     bool      `json:"isHead"`
}

// GameSaveGroup finds the cloud game group this local game belongs to. It
// prefers the local scope itself, then falls back to the canonical key the
// service would derive from this device's identity. Both lookups stay inside
// the local save type so the answer can never cross namespaces.
func (d *Daemon) GameSaveGroup(gameID string) (GameSaveGroup, error) {
	game, err := d.Store.GetGame(gameID)
	if err != nil {
		return GameSaveGroup{}, err
	}
	group := GameSaveGroup{
		GameName: game.Name,
		SaveType: cloud.SaveTypeScope(game.SaveType),
		Members:  []GameSaveGroupMember{},
	}

	cfg, err := d.Store.GetCloudConfig()
	if err != nil {
		return GameSaveGroup{}, err
	}
	if cfg.Provider != cloud.GameSaveProvider {
		group.State = gameSaveStatusDisabled
		group.Detail = "The configured cloud provider does not expose game-save game groups."
		return group, nil
	}
	if !cloud.Ready(cfg) {
		group.State = gameSaveStatusDisabled
		group.Detail = "game-save is not fully configured."
		return group, nil
	}
	group.Configured = true

	games, err := d.Cloud.ListGameSaveGames()
	if errors.Is(err, cloud.ErrGameSaveGamesUnsupported) {
		group.State = gameSaveStatusLegacy
		group.Detail = "The service does not expose game-save game groups."
		return group, nil
	}
	if err != nil {
		group.State = gameSaveStatusError
		if gameSaveStatusNetworkError(err) {
			group.State = gameSaveStatusOffline
		}
		group.Detail = fmt.Sprintf("Could not read game-save game groups: %v", err)
		return group, nil
	}
	group.Supported = true

	match := findGameSaveGroup(games, game)
	if match == nil {
		group.State = gameSaveGroupNotGrouped
		return group, nil
	}
	group.State = gameSaveGroupOK
	group.GameKey = match.Key
	if match.Name != "" {
		group.GameName = match.Name
	}
	group.Members = gameSaveGroupMembers(*match, game)
	return group, nil
}

// findGameSaveGroup first looks for this device's own scope, which is
// authoritative even if this device has not uploaded an identity yet. When
// that scope is absent (the game has never been backed up here), it uses the
// canonical keys derived from the same identity the upload path reports.
func findGameSaveGroup(games []cloud.GameSaveGame, game store.Game) *cloud.GameSaveGame {
	saveType := cloud.SaveTypeScope(game.SaveType)
	for i := range games {
		for _, member := range games[i].Scopes {
			if member.Type == saveType && member.GameID == game.ID {
				return &games[i]
			}
		}
	}

	identity := nativeGameSaveIdentity(game)
	keys := make([]string, 0, 2)
	if saveType == "switch" && identity.TitleID != "" {
		keys = append(keys, saveType+"/title:"+identity.TitleID)
	}
	if identity.SteamAppID != "" {
		keys = append(keys, saveType+"/steam:"+identity.SteamAppID)
	}
	for _, key := range keys {
		for i := range games {
			if games[i].Type == saveType && games[i].Key == key {
				return &games[i]
			}
		}
	}
	return nil
}

// gameSaveGroupMembers drops this device's own scope and presents the rest in
// the order the user expects: most recently active first. A member with no
// reported received time falls back to its head's receive time, which older
// service builds may be the only one to expose.
func gameSaveGroupMembers(group cloud.GameSaveGame, game store.Game) []GameSaveGroupMember {
	saveType := cloud.SaveTypeScope(game.SaveType)
	members := make([]GameSaveGroupMember, 0, len(group.Scopes))
	for _, scope := range group.Scopes {
		if scope.Type == saveType && scope.GameID == game.ID {
			continue
		}
		member := GameSaveGroupMember{
			Type:          scope.Type,
			GameID:        scope.GameID,
			Name:          scope.Name,
			Link:          scope.Link,
			LastVersionAt: scope.LastReceivedAt,
			SnapshotCount: scope.SnapshotCount,
			ConflictCount: scope.ConflictCount,
		}
		if scope.Head != nil {
			member.DeviceName = scope.Head.DeviceName
			member.Platform = scope.Head.Platform
			member.HeadID = scope.Head.ID
			member.HeadArchive = scope.Head.Archive
			if member.LastVersionAt.IsZero() {
				member.LastVersionAt = scope.Head.ReceivedAt
			}
		}
		members = append(members, member)
	}
	sort.Slice(members, func(i, j int) bool {
		if !members[i].LastVersionAt.Equal(members[j].LastVersionAt) {
			return members[i].LastVersionAt.After(members[j].LastVersionAt)
		}
		return members[i].GameID < members[j].GameID
	})
	return members
}

// GameSaveMemberVersions lists another device's restorable versions. It reads
// the scope through the ordinary scope endpoint and never changes a head.
func (d *Daemon) GameSaveMemberVersions(gameID, memberGameID string) ([]GameSaveMemberVersion, error) {
	game, err := d.Store.GetGame(gameID)
	if err != nil {
		return nil, err
	}
	if memberGameID == "" || memberGameID == game.ID {
		return nil, ErrCrossDeviceSameScope
	}
	if !gameSaveMemberIDPattern.MatchString(memberGameID) {
		return nil, ErrInvalidGameSaveMemberID
	}

	scope, err := d.Cloud.GetGameSaveScope(game.SaveType, memberGameID)
	if err != nil {
		return nil, err
	}
	versions := make([]GameSaveMemberVersion, 0, len(scope.Snapshots)+1)
	seen := make(map[string]bool, len(scope.Snapshots)+1)
	if scope.Head != nil && !scope.Head.Conflict {
		versions = append(versions, gameSaveMemberVersionFromScope(*scope.Head, true))
		seen[scope.Head.ID] = true
	}
	for _, snapshot := range scope.Snapshots {
		// Older service builds may include the head in Snapshots; the head was
		// already added with IsHead set, and a retained conflict copy is not a
		// restorable version.
		if snapshot.Conflict || seen[snapshot.ID] {
			continue
		}
		seen[snapshot.ID] = true
		versions = append(versions, gameSaveMemberVersionFromScope(snapshot, false))
	}
	sort.Slice(versions, func(i, j int) bool {
		if !versions[i].ReceivedAt.Equal(versions[j].ReceivedAt) {
			return versions[i].ReceivedAt.After(versions[j].ReceivedAt)
		}
		return versions[i].ID > versions[j].ID
	})
	return versions, nil
}

func gameSaveMemberVersionFromScope(snapshot cloud.GameSaveScopeSnapshot, isHead bool) GameSaveMemberVersion {
	return GameSaveMemberVersion{
		ID:         snapshot.ID,
		Archive:    snapshot.Archive,
		CreatedAt:  snapshot.CreatedAt,
		ReceivedAt: snapshot.ReceivedAt,
		DeviceName: snapshot.DeviceName,
		Platform:   snapshot.Platform,
		IsHead:     isHead,
	}
}
