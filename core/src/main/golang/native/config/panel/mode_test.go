package panel

import (
	"os"
	"path/filepath"
	"testing"
	"time"
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

func TestLockModeHeader(t *testing.T) {
	cases := []struct {
		name          string
		header        map[string][]string
		wantLocked    *bool
		wantPermanent bool
	}{
		{"молчание", map[string][]string{}, nil, false},
		{"lock", map[string][]string{"Clod-Lock-Mode": {"lock"}}, ptr(true), true},
		{"LOCK", map[string][]string{"Clod-Lock-Mode": {" LOCK "}}, ptr(true), true},
		{"true", map[string][]string{"Clod-Lock-Mode": {"true"}}, ptr(true), false},
		{"1", map[string][]string{"Clod-Lock-Mode": {"1"}}, ptr(true), false},
		{"yes", map[string][]string{"Clod-Lock-Mode": {"Yes"}}, ptr(true), false},
		{"on", map[string][]string{"Clod-Lock-Mode": {"on"}}, ptr(true), false},
		{"false", map[string][]string{"Clod-Lock-Mode": {"false"}}, ptr(false), false},
		{"0", map[string][]string{"Clod-Lock-Mode": {"0"}}, ptr(false), false},
		{"no", map[string][]string{"Clod-Lock-Mode": {"NO"}}, ptr(false), false},
		{"off", map[string][]string{"Clod-Lock-Mode": {"off"}}, ptr(false), false},
		{"мусор", map[string][]string{"Clod-Lock-Mode": {"locked"}}, nil, false},
		{"global-mode: false", map[string][]string{"Global-Mode": {"false"}}, ptr(true), false},
		{"global-mode: true", map[string][]string{"Global-Mode": {"true"}}, ptr(false), false},
		{"lock важнее global-mode", map[string][]string{"Clod-Lock-Mode": {"lock"}, "Global-Mode": {"true"}}, ptr(true), true},
		{"false важнее global-mode", map[string][]string{"Clod-Lock-Mode": {"false"}, "Global-Mode": {"false"}}, ptr(false), false},
		{"мусор уступает global-mode", map[string][]string{"Clod-Lock-Mode": {"locked"}, "Global-Mode": {"false"}}, ptr(true), false},
	}

	for _, c := range cases {
		var info Info
		ApplyHeaders(&info, c.header, "https://panel.example/sub")

		if (info.LockMode == nil) != (c.wantLocked == nil) ||
			(info.LockMode != nil && *info.LockMode != *c.wantLocked) ||
			info.LockPermanent != c.wantPermanent {
			t.Fatalf("%s: замок %v, постоянный %v", c.name, info.LockMode, info.LockPermanent)
		}
	}
}

func TestLockPermanentFollowsPanel(t *testing.T) {
	info := Info{LockMode: ptr(true), LockPermanent: true, UpdatedAt: 100, UpdateInterval: 3600}

	ApplyHeaders(&info, map[string][]string{"Clod-Lock-Mode": {"true"}}, "https://panel.example/sub")

	if info.LockPermanent {
		t.Fatal("постоянный замок живёт один ответ")
	}

	if info.UpdatedAt != 100 || info.UpdateInterval != 3600 {
		t.Fatalf("время обновления и интервал не должны трогаться разбором заголовков: %d, %d", info.UpdatedAt, info.UpdateInterval)
	}
}

func TestLockActive(t *testing.T) {
	const day = int64(24 * 60 * 60)
	const now = 100 * day
	const weekly = 7 * day

	cases := []struct {
		name string
		info Info
		want bool
	}{
		{"замка нет", Info{UpdatedAt: now - 100*day}, false},
		{"замок снят", Info{LockMode: ptr(false), UpdatedAt: now}, false},
		{"свежий", Info{LockMode: ptr(true), UpdatedAt: now - day}, true},
		{"ровно 72 часа", Info{LockMode: ptr(true), UpdatedAt: now - 3*day}, true},
		{"72 часа и секунда", Info{LockMode: ptr(true), UpdatedAt: now - 3*day - 1}, false},
		{"панель молчит четыре дня", Info{LockMode: ptr(true), UpdatedAt: now - 4*day}, false},
		{"короткий интервал не сжимает срок", Info{LockMode: ptr(true), UpdatedAt: now - 2*day, UpdateInterval: 3600}, true},
		{"недельный интервал растягивает срок", Info{LockMode: ptr(true), UpdatedAt: now - 4*day, UpdateInterval: weekly}, true},
		{"три недели и день", Info{LockMode: ptr(true), UpdatedAt: now - 22*day, UpdateInterval: weekly}, false},
		{"постоянный не истекает", Info{LockMode: ptr(true), LockPermanent: true, UpdatedAt: now - 100*day}, true},
		{"время обновления неизвестно", Info{LockMode: ptr(true)}, true},
		{"часы ушли назад", Info{LockMode: ptr(true), UpdatedAt: now + day}, true},
	}

	for _, c := range cases {
		if got := LockActive(c.info, now); got != c.want {
			t.Fatalf("%s: получено %v", c.name, got)
		}
	}
}

func TestMarkUpdatedKeepsPanel(t *testing.T) {
	dir := t.TempDir()

	Write(dir, Info{Title: "Провайдер", Mode: "global", LockMode: ptr(true), LockPermanent: true})

	MarkUpdated(dir, 1_700_000_000, 7200)

	info := Read(dir)

	if info.UpdatedAt != 1_700_000_000 || info.UpdateInterval != 7200 {
		t.Fatalf("время и интервал: %d, %d", info.UpdatedAt, info.UpdateInterval)
	}

	if info.Title != "Провайдер" || info.Mode != "global" || info.LockMode == nil || !*info.LockMode || !info.LockPermanent {
		t.Fatalf("остальное содержимое panel.json потеряно: %+v", info)
	}
}

func TestOldPanelKeepsTemporaryLock(t *testing.T) {
	dir := t.TempDir()

	if err := os.WriteFile(filepath.Join(dir, panelFileName), []byte(`{"lockMode":true,"mode":"global"}`), 0o644); err != nil {
		t.Fatal(err)
	}

	info := Read(dir)

	if info.LockPermanent || info.UpdatedAt != 0 || info.UpdateInterval != 0 {
		t.Fatalf("старый panel.json: %+v", info)
	}

	if !LockActive(WithUpdatedAt(dir, info), 1_900_000_000) {
		t.Fatal("без config.yaml время обновления неизвестно, замок держится")
	}

	config := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(config, []byte("mode: global\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	updated := time.Unix(1_700_000_000, 0)
	if err := os.Chtimes(config, updated, updated); err != nil {
		t.Fatal(err)
	}

	known := WithUpdatedAt(dir, info)

	if known.UpdatedAt != 1_700_000_000 {
		t.Fatalf("время из config.yaml: %d", known.UpdatedAt)
	}

	if LockActive(known, 1_900_000_000) {
		t.Fatal("старый panel.json: замок истекает по времени config.yaml")
	}

	if !LockActive(known, 1_700_000_000+lockGraceSeconds) {
		t.Fatal("старый panel.json: свежий config.yaml держит замок")
	}

	if WithUpdatedAt(dir, Info{UpdatedAt: 42}).UpdatedAt != 42 {
		t.Fatal("записанное время обновления важнее config.yaml")
	}
}

func TestLockUntil(t *testing.T) {
	const day = int64(24 * 60 * 60)

	cases := []struct {
		name string
		info Info
		want int64
	}{
		{"замка нет", Info{UpdatedAt: day}, 0},
		{"замок снят", Info{LockMode: ptr(false), UpdatedAt: day}, 0},
		{"постоянный", Info{LockMode: ptr(true), LockPermanent: true, UpdatedAt: day}, 0},
		{"время неизвестно", Info{LockMode: ptr(true)}, 0},
		{"временный", Info{LockMode: ptr(true), UpdatedAt: day}, 4 * day},
		{"недельный интервал", Info{LockMode: ptr(true), UpdatedAt: day, UpdateInterval: 7 * day}, 22 * day},
	}

	for _, c := range cases {
		if got := LockUntil(c.info); got != c.want {
			t.Fatalf("%s: получено %d", c.name, got)
		}
	}
}

func ptr(value bool) *bool {
	return &value
}
