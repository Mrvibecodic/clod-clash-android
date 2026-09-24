package panel

import (
	"errors"
	"io/fs"
	"os"
	P "path"
	"testing"
)

func writeProfileFile(t *testing.T, dir string, name string, content string) {
	t.Helper()

	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}

	if err := os.WriteFile(P.Join(dir, name), []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestReadProfileFileTakesLiveDirectory(t *testing.T) {
	live := P.Join(t.TempDir(), "uuid")

	writeProfileFile(t, live, "config.yaml", "fresh")
	writeProfileFile(t, live+staleSuffix, "config.yaml", "old")

	data, err := ReadProfileFile(live, "config.yaml")
	if err != nil || string(data) != "fresh" {
		t.Fatalf("got %q, %v", data, err)
	}
}

func TestReadProfileFileFallsBackToParkedVersion(t *testing.T) {
	live := P.Join(t.TempDir(), "uuid")

	writeProfileFile(t, live+staleSuffix, "config.yaml", "old")

	data, err := ReadProfileFile(live, "config.yaml")
	if err != nil || string(data) != "old" {
		t.Fatalf("got %q, %v", data, err)
	}
}

func TestReadProfileFileMissingInLiveIsMissing(t *testing.T) {
	live := P.Join(t.TempDir(), "uuid")

	writeProfileFile(t, live, "config.yaml", "fresh")
	writeProfileFile(t, live+staleSuffix, "tun.json", "{}")

	_, err := ReadProfileFile(live, "tun.json")
	if !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("parked copy must not stand in for a file the live profile lacks: %v", err)
	}
}

func TestReadProfileFileWithoutProfile(t *testing.T) {
	_, err := ReadProfileFile(P.Join(t.TempDir(), "uuid"), "config.yaml")
	if !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("got %v", err)
	}
}

func TestReadProfileFileAfterBrokenSwapTakesWholeParkedVersion(t *testing.T) {
	live := P.Join(t.TempDir(), "uuid")

	writeProfileFile(t, live, "late.yaml", "written by core")
	writeProfileFile(t, live+staleSuffix, ProfileConfigFile, "old")

	data, err := ReadProfileFile(live, ProfileConfigFile)
	if err != nil || string(data) != "old" {
		t.Fatalf("got %q, %v", data, err)
	}
}

func TestReadProfileFileIgnoresRemnantOfBrokenSwap(t *testing.T) {
	live := P.Join(t.TempDir(), "uuid")

	writeProfileFile(t, live, "late.yaml", "written by core")
	writeProfileFile(t, live+staleSuffix, ProfileConfigFile, "old")

	_, err := ReadProfileFile(live, "late.yaml")
	if !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("repair drops the remnant, so its files must not be read: %v", err)
	}
}
