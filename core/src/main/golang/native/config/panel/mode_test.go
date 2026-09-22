package panel

import "testing"

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
		{"шаблон неизвестен", "", nil, nil, false, "", ModeTemplate},
		{"шаблон неизвестен, есть выбор", "", &rule, &direct, false, direct, ModeChoice},
	}

	for _, c := range cases {
		mode, source := ResolveMode(c.template, c.persist, c.choice, c.locked)

		if mode != c.wantMode || source != c.wantSource {
			t.Fatalf("%s: получено (%q, %q), ожидалось (%q, %q)", c.name, mode, source, c.wantMode, c.wantSource)
		}
	}
}
