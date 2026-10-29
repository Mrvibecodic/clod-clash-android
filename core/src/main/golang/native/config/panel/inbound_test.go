package panel

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func ReadInboundPrefs(dir string) InboundPrefs {
	var prefs InboundPrefs

	bytes, err := os.ReadFile(inboundPath(dir))
	if err != nil {
		return prefs
	}

	_ = json.Unmarshal(bytes, &prefs)

	return prefs
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
