//go:build windows

package main

import "golang.org/x/sys/windows"

// systemLanguage is the first of the user's preferred display languages.
func systemLanguage() string {
	langs, err := windows.GetUserPreferredUILanguages(windows.MUI_LANGUAGE_NAME)
	if err != nil || len(langs) == 0 {
		return ""
	}
	return langs[0]
}
