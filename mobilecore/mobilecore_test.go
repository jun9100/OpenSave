package mobilecore

import "testing"

func TestSha256(t *testing.T) {
	if got := Sha256([]byte("opensave")); got != "7c20a69d509a0354159ce6af891c989f10e94554c4204c43573832bb7ae93757" {
		t.Fatalf("Sha256() = %s", got)
	}
}

func TestBridgeResultExposesErrors(t *testing.T) {
	if got := ManifestHash("/path/that/does/not/exist"); got == "" || got[0] != '{' {
		t.Fatalf("ManifestHash() = %q", got)
	}
}
