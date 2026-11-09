//go:build windows

// Package sysintegration handles OS-level integration: start-on-boot
// registration and (on Windows) the system tray.
package sysintegration

import (
	"fmt"
	"os"

	"golang.org/x/sys/windows/registry"
)

const runKeyPath = `Software\Microsoft\Windows\CurrentVersion\Run`
const runValueName = "OpenSave"

// strayRunValueName is what the 2.5 and 2.6 installers wrote for their "start
// when Windows starts" checkbox: the product name after the rename, not the
// name this code keeps. A machine with both started the app twice at every
// boot. It is never written; SetAutostart removes it, and AutostartEnabled
// counts it so that a box ticked in one of those installers is still adopted.
const strayRunValueName = "SaveVault"

// SetAutostart registers or removes OpenSave in the current user's Run
// key (no admin rights needed).
func SetAutostart(enabled bool) error {
	key, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE|registry.QUERY_VALUE)
	if err != nil {
		return fmt.Errorf("open Run key: %w", err)
	}
	defer key.Close()

	if err := key.DeleteValue(strayRunValueName); err != nil && err != registry.ErrNotExist {
		return fmt.Errorf("remove duplicate autostart entry: %w", err)
	}

	if !enabled {
		err := key.DeleteValue(runValueName)
		if err != nil && err != registry.ErrNotExist {
			return fmt.Errorf("remove autostart entry: %w", err)
		}
		return nil
	}

	exe, err := os.Executable()
	if err != nil {
		return fmt.Errorf("resolve executable path: %w", err)
	}
	// Launched hidden: at boot the app belongs in the tray, not over
	// whatever the person sat down to do. See StartHiddenFlag.
	want := `"` + exe + `" ` + StartHiddenFlag
	// Skipped when already right. This is also called at every startup to
	// repair an entry written before the flag existed, or one pointing at a
	// binary that has since moved, and a registry write per launch for a
	// value that has not changed is noise in anyone's audit log.
	if current, _, readErr := key.GetStringValue(runValueName); readErr == nil && current == want {
		return nil
	}
	if err := key.SetStringValue(runValueName, want); err != nil {
		return fmt.Errorf("set autostart entry: %w", err)
	}
	return nil
}

// AutostartEnabled reports whether a Run entry exists, under the app's name
// or the one the 2.5 and 2.6 installers used.
func AutostartEnabled() bool {
	key, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer key.Close()
	for _, name := range []string{runValueName, strayRunValueName} {
		if _, _, err := key.GetStringValue(name); err == nil {
			return true
		}
	}
	return false
}
