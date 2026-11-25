package panel

import (
	"os"
	"path/filepath"
	"testing"
)

// Каталог на месте файла: подмена через временный не пройдёт, как при ENOSPC/EIO.
func occupy(t *testing.T, path string) {
	t.Helper()

	if err := os.MkdirAll(filepath.Join(path, "busy"), 0o700); err != nil {
		t.Fatal(err)
	}
}

func TestPanelWriteReportsFailure(t *testing.T) {
	dir := t.TempDir()
	occupy(t, panelPath(dir))

	if err := Write(dir, Info{Title: "x"}); err == nil {
		t.Fatal("сбой записи panel.json не дошёл до вызвавшего")
	}
}

func TestProfileFilesWriteReportsTunFailure(t *testing.T) {
	dir := t.TempDir()
	occupy(t, tunPath(dir))

	if err := (ProfileFiles{Tun: TunPrefs{AllApps: true}}).Write(dir); err == nil {
		t.Fatal("сбой записи tun.json не дошёл до вызвавшего")
	}
}

func TestProfileFilesWriteReportsStaleTunNotRemoved(t *testing.T) {
	dir := t.TempDir()
	occupy(t, tunPath(dir))

	if err := (ProfileFiles{}).Write(dir); err == nil {
		t.Fatal("несработавшее удаление tun.json не дошло до вызвавшего")
	}
}

func TestProfileFilesWriteReportsInboundFailure(t *testing.T) {
	dir := t.TempDir()
	occupy(t, inboundPath(dir))

	if err := (ProfileFiles{Inbound: InboundPrefs{MixedPort: 7890}}).Write(dir); err == nil {
		t.Fatal("сбой записи inbound.json не дошёл до вызвавшего")
	}
}

func TestProfileFilesWriteWithoutFilesIsFine(t *testing.T) {
	if err := (ProfileFiles{}).Write(t.TempDir()); err != nil {
		t.Fatalf("пустые настройки без файлов — не ошибка: %v", err)
	}
}
