package panel

import (
	"os"
	"path/filepath"
	"testing"
)

func TestInboundPrefsLocalProxyPort(t *testing.T) {
	cases := []struct {
		prefs    InboundPrefs
		expected int
	}{
		{InboundPrefs{MixedPort: 7890}, 7890},
		{InboundPrefs{HttpPort: 8080}, 8080},
		{InboundPrefs{MixedPort: 7890, HttpPort: 8080}, 7890},
		{InboundPrefs{}, 0},
	}

	for _, item := range cases {
		if got := item.prefs.LocalProxyPort(); got != item.expected {
			t.Errorf("LocalProxyPort of %+v = %d, expected %d", item.prefs, got, item.expected)
		}
	}
}

func TestWriteAndReadInboundPrefs(t *testing.T) {
	dir := t.TempDir()

	WriteInboundPrefs(dir, InboundPrefs{MixedPort: 7891})

	if got := ReadInboundPrefs(dir); got.MixedPort != 7891 {
		t.Errorf("ReadInboundPrefs = %+v, expected mixed port 7891", got)
	}

	WriteInboundPrefs(dir, InboundPrefs{})

	if _, err := os.Stat(filepath.Join(dir, inboundFileName)); !os.IsNotExist(err) {
		t.Errorf("inbound.json expected to be removed, got %v", err)
	}

	if got := ReadInboundPrefs(dir); !got.IsEmpty() {
		t.Errorf("ReadInboundPrefs of empty dir = %+v, expected empty", got)
	}
}
