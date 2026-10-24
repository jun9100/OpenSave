package cloud

import (
	"fmt"
	"net/url"
	"path"
	"strings"
)

// NormalizeEdenSyncdURL turns the eden-syncd service address into the
// dedicated WebDAV collection used by OpenSave. It accepts an already-complete
// /opensave URL as well, which makes saved settings and copy-pasted examples
// idempotent.
func NormalizeEdenSyncdURL(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", nil
	}
	parsed, err := url.Parse(value)
	if err != nil || parsed == nil {
		return "", fmt.Errorf("invalid EDEN_SYNCD service address")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", fmt.Errorf("EDEN_SYNCD service address must use http or https")
	}
	if parsed.Host == "" {
		return "", fmt.Errorf("EDEN_SYNCD service address must include a host")
	}
	if parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", fmt.Errorf("EDEN_SYNCD service address cannot include credentials, a query, or a fragment")
	}
	escapedPath := strings.ToLower(parsed.EscapedPath())
	if strings.Contains(escapedPath, "%2f") || strings.Contains(escapedPath, "%5c") {
		return "", fmt.Errorf("EDEN_SYNCD service address cannot contain encoded path separators")
	}
	basePath := strings.TrimRight(parsed.Path, "/")
	for _, segment := range strings.Split(strings.Trim(basePath, "/"), "/") {
		if segment == "." || segment == ".." {
			return "", fmt.Errorf("EDEN_SYNCD service address cannot contain relative path segments")
		}
	}
	if strings.EqualFold(path.Base(basePath), "opensave") {
		basePath = path.Dir(basePath)
	}
	parsed.Path = strings.TrimRight(basePath, "/") + "/opensave"
	parsed.RawPath = ""
	return strings.TrimRight(parsed.String(), "/"), nil
}
