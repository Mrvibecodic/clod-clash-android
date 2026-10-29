package panel

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func ReadTunPrefs(dir string) TunPrefs {
	var prefs TunPrefs

	bytes, err := os.ReadFile(tunPath(dir))
	if err != nil {
		return prefs
	}

	_ = json.Unmarshal(bytes, &prefs)

	prefs.Stack = NormalizeTunStack(prefs.Stack)
	prefs.IncludePackages = SanitizePackages(prefs.IncludePackages)
	prefs.ExcludePackages = SanitizePackages(prefs.ExcludePackages)

	return prefs
}

func TestNormalizeTunStack(t *testing.T) {
	cases := map[string]string{
		"system":  "system",
		"System":  "system",
		" gVisor": "gvisor",
		"MIXED":   "mixed",
		"Mips":    "mips",
		"lwip":    "",
		"":        "",
	}

	for input, expected := range cases {
		if got := NormalizeTunStack(input); got != expected {
			t.Errorf("NormalizeTunStack(%q) = %q, expected %q", input, got, expected)
		}
	}
}

func TestSanitizePackages(t *testing.T) {
	got := SanitizePackages([]string{" com.a ", "", "com.b", "com.a"})
	expected := []string{"com.a", "com.b"}

	if !reflect.DeepEqual(got, expected) {
		t.Errorf("SanitizePackages = %v, expected %v", got, expected)
	}

	if SanitizePackages(nil) != nil {
		t.Error("SanitizePackages(nil) expected nil")
	}

	if SanitizePackages([]string{" ", ""}) != nil {
		t.Error("SanitizePackages(blank) expected nil")
	}
}

func TestMergePackages(t *testing.T) {
	got := MergePackages([]string{"com.a"}, []string{"com.b", "com.a"})
	expected := []string{"com.a", "com.b"}

	if !reflect.DeepEqual(got, expected) {
		t.Errorf("MergePackages = %v, expected %v", got, expected)
	}
}

func TestTunPrefsReadWrite(t *testing.T) {
	dir := t.TempDir()

	WriteTunPrefs(dir, TunPrefs{
		Stack:           "gvisor",
		ExcludePackages: []string{"com.a", "com.b"},
	})

	got := ReadTunPrefs(dir)

	if got.Stack != "gvisor" {
		t.Errorf("Stack = %q", got.Stack)
	}

	if !reflect.DeepEqual(got.ExcludePackages, []string{"com.a", "com.b"}) {
		t.Errorf("ExcludePackages = %v", got.ExcludePackages)
	}

	if got.IncludePackages != nil {
		t.Errorf("IncludePackages = %v", got.IncludePackages)
	}
}

func TestTunPrefsWriteEmptyRemoves(t *testing.T) {
	dir := t.TempDir()

	WriteTunPrefs(dir, TunPrefs{Stack: "system"})

	if _, err := os.Stat(filepath.Join(dir, tunFileName)); err != nil {
		t.Fatalf("tun.json expected to exist: %v", err)
	}

	WriteTunPrefs(dir, TunPrefs{})

	if _, err := os.Stat(filepath.Join(dir, tunFileName)); !os.IsNotExist(err) {
		t.Fatalf("tun.json expected to be removed, got %v", err)
	}
}

// Kotlin (TunPrefs) читает режим провайдера по ключу "allApps"; без списков и
// стека tun.json не должен удаляться, иначе режим потеряется.
func TestTunPrefsAllAppsAlone(t *testing.T) {
	dir := t.TempDir()

	WriteTunPrefs(dir, TunPrefs{AllApps: true})

	written, err := os.ReadFile(filepath.Join(dir, tunFileName))
	if err != nil {
		t.Fatalf("tun.json expected to exist: %v", err)
	}

	if !strings.Contains(string(written), `"allApps":true`) {
		t.Fatalf("режим должен лежать ключом allApps: %s", written)
	}

	if !ReadTunPrefs(dir).AllApps {
		t.Fatal("режим не пережил запись tun.json")
	}

	WriteTunPrefs(dir, TunPrefs{})

	if _, err := os.Stat(filepath.Join(dir, tunFileName)); !os.IsNotExist(err) {
		t.Fatalf("tun.json expected to be removed, got %v", err)
	}
}

// Загрузка без скачивания берёт режим провайдера из panel.json: он должен
// пережить запись и попасть в настройки туннеля.
func TestAllAppsFromPanelFile(t *testing.T) {
	dir := t.TempDir()

	Write(dir, Info{AllApps: true})

	written, err := os.ReadFile(panelPath(dir))
	if err != nil {
		t.Fatal(err)
	}

	if !strings.Contains(string(written), `"allApps":true`) {
		t.Fatalf("в panel.json режим должен лежать ключом allApps: %s", written)
	}

	prefs := TunPrefsOf([]string{" com.a ", "com.a"}, nil, Read(dir))

	if !prefs.AllApps || !reflect.DeepEqual(prefs.IncludePackages, []string{"com.a"}) || prefs.ExcludePackages != nil {
		t.Fatalf("TunPrefsOf = %+v", prefs)
	}

	if TunPrefsOf(nil, nil, Info{}).AllApps {
		t.Fatal("без заголовка режим провайдера не должен включаться")
	}
}

// Разбор конфига зовёт запись на каждой загрузке и пробе: тот же tun.json не переписывается.
func TestTunPrefsSameNotRewritten(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, tunFileName)

	prefs := TunPrefs{Stack: "gvisor", IncludePackages: []string{"com.a"}}

	WriteTunPrefs(dir, prefs)

	old := time.Unix(1_000_000_000, 0)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}

	WriteTunPrefs(dir, TunPrefs{Stack: "gvisor", IncludePackages: []string{"com.a"}})

	if stat, err := os.Stat(path); err != nil || !stat.ModTime().Equal(old) {
		t.Fatalf("tun.json rewritten with the same content: %v", err)
	}

	WriteTunPrefs(dir, TunPrefs{Stack: "system"})

	if got := ReadTunPrefs(dir); got.Stack != "system" || got.IncludePackages != nil {
		t.Errorf("changed prefs not written: %+v", got)
	}
}

func TestTunPrefsReadInvalid(t *testing.T) {
	dir := t.TempDir()

	if err := os.WriteFile(filepath.Join(dir, tunFileName), []byte("{"), 0o644); err != nil {
		t.Fatal(err)
	}

	if got := ReadTunPrefs(dir); !got.IsEmpty() {
		t.Errorf("expected empty prefs, got %+v", got)
	}
}

func TestStringsFromAny(t *testing.T) {
	got := StringsFromAny([]any{"com.a", 1, "com.b"})

	if !reflect.DeepEqual(got, []string{"com.a", "com.b"}) {
		t.Errorf("StringsFromAny = %v", got)
	}

	if StringsFromAny("not a list") != nil {
		t.Error("StringsFromAny(non-list) expected nil")
	}
}
