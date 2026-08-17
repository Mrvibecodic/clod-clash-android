package panel

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestNormalizeTunStack(t *testing.T) {
	cases := map[string]string{
		"system":  "system",
		"System":  "system",
		" gVisor": "gvisor",
		"MIXED":   "mixed",
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

func TestTunPrefsReadInvalid(t *testing.T) {
	dir := t.TempDir()

	if err := os.WriteFile(filepath.Join(dir, tunFileName), []byte("{"), 0o644); err != nil {
		t.Fatal(err)
	}

	if got := ReadTunPrefs(dir); !got.IsEmpty() {
		t.Errorf("expected empty prefs, got %+v", got)
	}
}

func TestTunPrefsReadNormalizes(t *testing.T) {
	dir := t.TempDir()

	data := []byte(`{"stack":"LWIP","includePackages":[" com.a ","com.a",""]}`)

	if err := os.WriteFile(filepath.Join(dir, tunFileName), data, 0o644); err != nil {
		t.Fatal(err)
	}

	got := ReadTunPrefs(dir)

	if got.Stack != "" {
		t.Errorf("Stack = %q, expected empty", got.Stack)
	}

	if !reflect.DeepEqual(got.IncludePackages, []string{"com.a"}) {
		t.Errorf("IncludePackages = %v", got.IncludePackages)
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
