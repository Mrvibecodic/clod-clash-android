package common

import (
	"os"
	"path/filepath"
	"testing"
)

func TestWriteFileReplacesTheWholeFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file.json")

	for _, content := range []string{"первое, длинное содержимое", "второе"} {
		if err := WriteFile(path, []byte(content), false); err != nil {
			t.Fatal(err)
		}

		if got, _ := os.ReadFile(path); string(got) != content {
			t.Fatalf("в файле %q, ждали %q", got, content)
		}
	}

	if err := WriteFile(path, []byte("третье"), true); err != nil {
		t.Fatal(err)
	}

	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatalf("временный файл остался: %v", err)
	}
}

func TestAFailedRenameLeavesNoTemp(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file.json")
	if err := os.MkdirAll(filepath.Join(path, "busy"), 0o700); err != nil {
		t.Fatal(err)
	}

	if err := WriteFile(path, []byte("{}"), false); err == nil {
		t.Fatal("запись поверх непустого каталога прошла")
	}

	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatalf("временный файл остался: %v", err)
	}
}

func TestATempThatCannotBeOpenedIsLeftAlone(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file.json")
	if err := os.MkdirAll(filepath.Join(path+".tmp", "busy"), 0o700); err != nil {
		t.Fatal(err)
	}

	if err := WriteFile(path, []byte("{}"), true); err == nil {
		t.Fatal("запись через занятый временный прошла")
	}

	if _, err := os.Stat(filepath.Join(path+".tmp", "busy")); err != nil {
		t.Fatalf("чужой временный тронут: %v", err)
	}
}
