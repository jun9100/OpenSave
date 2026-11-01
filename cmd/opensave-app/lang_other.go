//go:build !windows

package main

import "os"

// systemLanguage is the locale the session runs in, as POSIX spells it
// (zh_CN.UTF-8); LC_ALL overrides LC_MESSAGES overrides LANG.
func systemLanguage() string {
	for _, k := range []string{"LC_ALL", "LC_MESSAGES", "LANG"} {
		if v := os.Getenv(k); v != "" && v != "C" && v != "POSIX" {
			return v
		}
	}
	return ""
}
