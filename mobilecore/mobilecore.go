// Package mobilecore is the public, platform-neutral boundary for the Android
// bridge. Keep it free of desktop UI, fsnotify, registry, and SQLite imports.
package mobilecore

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"

	"github.com/opensave/opensave/internal/delta"
	"github.com/opensave/opensave/internal/snapshot"
	"github.com/opensave/opensave/internal/switchtitle"
)

// Version reports the bridge contract version exposed to Android.
func Version() string {
	return "mobilecore-v1"
}

// Sha256 returns the digest Android can use for a copied workspace file.
func Sha256(data []byte) string {
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}

func buildManifestJSON(root string) (string, error) {
	manifest, err := delta.BuildManifest(root)
	if err != nil {
		return "", err
	}
	raw, err := json.Marshal(manifest)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

func manifestHash(root string) (string, error) {
	manifest, err := delta.BuildManifest(root)
	if err != nil {
		return "", err
	}
	return manifest.ContentHash(), nil
}

func createSnapshot(root, output string) (string, error) {
	skipped, err := snapshot.ZipPath(root, output)
	if err != nil {
		return "", err
	}
	raw, err := json.Marshal(struct {
		Skipped []string `json:"skipped"`
	}{Skipped: skipped})
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

func restoreSnapshot(archive, target string) error {
	return snapshot.UnzipTo(archive, target)
}

// BuildManifestJSON builds the desktop-compatible manifest. The JSON result
// wrapper keeps errors visible to gomobile callers, whose generated Java API
// cannot expose Go's error return directly.
func BuildManifestJSON(root string) string {
	value, err := buildManifestJSON(root)
	return bridgeResult(value, err)
}

// ManifestHash returns a bridge result containing the stable manifest hash.
func ManifestHash(root string) string {
	value, err := manifestHash(root)
	return bridgeResult(value, err)
}

// CreateSnapshot writes an OpenSave-compatible ZIP and returns a bridge result
// containing a JSON report of skipped paths. The output must be outside root.
func CreateSnapshot(root, output string) string {
	value, err := createSnapshot(root, output)
	return bridgeResult(value, err)
}

// RestoreSnapshot extracts an OpenSave-compatible ZIP into a private
// workspace and returns a bridge result. The Android layer performs final SAF
// write-back after this operation succeeds.
func RestoreSnapshot(archive, target string) string {
	return bridgeResult("", restoreSnapshot(archive, target))
}

// SwitchTitleID extracts a Switch title ID from a save path or tracked game
// ID. It is intentionally small so Android can share the desktop rule.
func SwitchTitleID(gameID, savePath string) string {
	return switchtitle.Of(gameID, savePath)
}

func bridgeResult(value string, err error) string {
	result := struct {
		OK    bool   `json:"ok"`
		Value string `json:"value,omitempty"`
		Error string `json:"error,omitempty"`
	}{OK: err == nil, Value: value}
	if err != nil {
		result.Error = err.Error()
	}
	raw, marshalErr := json.Marshal(result)
	if marshalErr != nil {
		return `{"ok":false,"error":"could not encode bridge result"}`
	}
	return string(raw)
}
