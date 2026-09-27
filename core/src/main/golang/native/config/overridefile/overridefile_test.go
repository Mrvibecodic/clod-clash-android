package overridefile

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMissingFileReadsAsFactory(t *testing.T) {
	got, err := Read(filepath.Join(t.TempDir(), "override.json"), "{}")
	if err != nil || got != "{}" {
		t.Fatalf("нет файла — заводские настройки без ошибки, получено %q, %v", got, err)
	}
}

func TestUnreadableFileIsAnErrorNotFactory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "override.json")

	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}

	got, err := Read(path, "{}")
	if err == nil {
		t.Fatalf("нечитаемый файл выдан за настройки %q", got)
	}
}

func TestWriteThenRead(t *testing.T) {
	path := filepath.Join(t.TempDir(), "override.json")

	if err := Write(path, `{"ipv6":true}`); err != nil {
		t.Fatal(err)
	}

	got, err := Read(path, "{}")
	if err != nil || got != `{"ipv6":true}` {
		t.Fatalf("прочитано %q, %v", got, err)
	}
}

func TestFailedWriteIsReportedAndKeepsTheFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "override.json")

	if err := Write(path, `{"ipv6":true}`); err != nil {
		t.Fatal(err)
	}

	// Временный файл на месте каталога: открыть его на запись нельзя.
	if err := os.Mkdir(path+".tmp", 0700); err != nil {
		t.Fatal(err)
	}

	if err := Write(path, `{}`); err == nil {
		t.Fatal("неудачная запись доложена как успех")
	}

	got, err := Read(path, "{}")
	if err != nil || got != `{"ipv6":true}` {
		t.Fatalf("неудачная запись тронула прежние настройки: %q, %v", got, err)
	}
}

func TestRemove(t *testing.T) {
	path := filepath.Join(t.TempDir(), "override.json")

	if err := Remove(path); err != nil {
		t.Fatalf("удаление несуществующего файла — не ошибка: %v", err)
	}

	if err := Write(path, `{}`); err != nil {
		t.Fatal(err)
	}

	if err := Remove(path); err != nil {
		t.Fatal(err)
	}

	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("файл остался: %v", err)
	}
}

func TestFailedRemoveIsReported(t *testing.T) {
	path := filepath.Join(t.TempDir(), "override.json")

	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}

	if err := os.WriteFile(filepath.Join(path, "x"), nil, 0600); err != nil {
		t.Fatal(err)
	}

	if err := Remove(path); err == nil {
		t.Fatal("неудачное удаление доложено как успех")
	}
}

func TestStoreReadsDiskOnceAndMirrorsOwnWrites(t *testing.T) {
	path := filepath.Join(t.TempDir(), "override.json")
	store := NewStore(path, "{}")

	if got, err := store.Read(); err != nil || got != "{}" {
		t.Fatalf("нет файла — заводские: %q, %v", got, err)
	}

	if err := store.Write(`{"ipv6":true}`); err != nil {
		t.Fatal(err)
	}

	// Чужая запись мимо хранилища не видна: диск больше не читается.
	if err := os.WriteFile(path, []byte(`{"ipv6":false}`), 0600); err != nil {
		t.Fatal(err)
	}

	if got, err := store.Read(); err != nil || got != `{"ipv6":true}` {
		t.Fatalf("зеркало разошлось с записанным: %q, %v", got, err)
	}

	if err := store.Remove(); err != nil {
		t.Fatal(err)
	}

	if got, err := store.Read(); err != nil || got != "{}" {
		t.Fatalf("после удаления не заводские: %q, %v", got, err)
	}
}

func TestStoreKeepsReadErrorUntilWrite(t *testing.T) {
	path := filepath.Join(t.TempDir(), "override.json")

	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}

	store := NewStore(path, "{}")

	if _, err := store.Read(); err == nil {
		t.Fatal("нечитаемый файл выдан за настройки")
	}

	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}

	if _, err := store.Read(); err == nil {
		t.Fatal("ошибка чтения забыта без записи")
	}

	if err := store.Write(`{}`); err != nil {
		t.Fatal(err)
	}

	if got, err := store.Read(); err != nil || got != `{}` {
		t.Fatalf("запись не сняла ошибку: %q, %v", got, err)
	}
}
