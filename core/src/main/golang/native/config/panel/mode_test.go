package panel

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolveMode(t *testing.T) {
	rule, global, direct := "rule", "global", "direct"

	cases := []struct {
		name       string
		template   string
		persist    *string
		choice     *string
		locked     bool
		wantMode   string
		wantSource ModeSource
	}{
		{"только шаблон", rule, nil, nil, false, rule, ModeTemplate},
		{"переопределение поверх шаблона", rule, &global, nil, false, global, ModeOverride},
		{"выбор поверх переопределения", rule, &global, &direct, false, direct, ModeChoice},
		{"выбор без переопределения", rule, nil, &global, false, global, ModeChoice},
		{"замок главнее выбора и переопределения", global, &direct, &direct, true, global, ModeLocked},
		{"замок без выбора", global, nil, nil, true, global, ModeLocked},
		{"шаблон неизвестен", "", nil, nil, false, rule, ModeTemplate},
		{"шаблон неизвестен, замок", "", &global, &direct, true, rule, ModeLocked},
		{"шаблон неизвестен, есть выбор", "", &rule, &direct, false, direct, ModeChoice},
	}

	for _, c := range cases {
		mode, source := ResolveMode(c.template, c.persist, c.choice, c.locked)

		if mode != c.wantMode || source != c.wantSource {
			t.Fatalf("%s: получено (%q, %q), ожидалось (%q, %q)", c.name, mode, source, c.wantMode, c.wantSource)
		}
	}
}

func TestReadWithModeFillsOldPanelOnce(t *testing.T) {
	dir := t.TempDir()

	if err := os.WriteFile(filepath.Join(dir, panelFileName), []byte(`{"title":"Old","main":"Proxy"}`), 0o644); err != nil {
		t.Fatal(err)
	}

	calls := 0
	subscription := func() string {
		calls++

		return "global"
	}

	if got := ReadWithMode(dir, subscription).Mode; got != "global" {
		t.Fatalf("режим шаблона из подписки: получено %q", got)
	}

	info := ReadWithMode(dir, subscription)
	if info.Mode != "global" || info.Title != "Old" || info.Main != "Proxy" {
		t.Fatalf("panel.json после дозаписи режима: %+v", info)
	}

	if calls != 1 {
		t.Fatalf("подписка читалась %d раз, ожидался один", calls)
	}
}

func TestReadWithModeFallsBackToRule(t *testing.T) {
	dir := t.TempDir()

	if got := ReadWithMode(dir, func() string { return "" }).Mode; got != DefaultMode {
		t.Fatalf("нечитаемая подписка: получено %q", got)
	}

	if got := Read(dir).Mode; got != DefaultMode {
		t.Fatalf("режим по правилам не записан явно: %q", got)
	}
}

func TestReadWithModeKeepsKnownMode(t *testing.T) {
	dir := t.TempDir()

	Write(dir, Info{Mode: "direct"})

	got := ReadWithMode(dir, func() string {
		t.Fatal("подписка не должна читаться, когда режим уже записан")

		return ""
	}).Mode

	if got != "direct" {
		t.Fatalf("записанный режим: получено %q", got)
	}
}
