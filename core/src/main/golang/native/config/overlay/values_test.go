package overlay

import (
	"reflect"
	"testing"
)

func ptr(s string) *string { return &s }

func shape(values Values) map[string][]string {
	result := map[string][]string{}

	for _, group := range values.Groups {
		for _, line := range group.Lines {
			result[group.ID] = append(result[group.ID], line.Key)
		}
	}

	return result
}

type differencesCase struct {
	name     string
	template string
	persist  string
	mode     Mode
	want     map[string][]string
}

func runDifferences(t *testing.T, cases []differencesCase) {
	t.Helper()

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			persist := c.persist
			if persist == "" {
				persist = `{}`
			}

			values, err := Differences([]byte(c.template), persist, c.mode)
			if err != nil {
				t.Fatal(err)
			}

			if got := shape(values); !reflect.DeepEqual(got, c.want) {
				t.Fatalf("получено %v, ожидалось %v", got, c.want)
			}
		})
	}
}

func TestDifferences(t *testing.T) {
	runDifferences(t, []differencesCase{
		{
			name:     "режим: выбор подписки перебивает шаблон",
			template: "mode: rule\n",
			mode:     Mode{Choice: ptr("global")},
			want:     map[string][]string{GroupMode: {modeKey}},
		},
		{
			name:     "режим: «Переопределение» перебивает шаблон",
			template: "mode: Global\n",
			persist:  `{"mode":"direct"}`,
			mode:     Mode{Persist: ptr("direct")},
			want:     map[string][]string{GroupMode: {modeKey}},
		},
		{
			name:     "режим: выбор подписки как в шаблоне — «Переопределение» не действует",
			template: "mode: global\n",
			persist:  `{"mode":"direct"}`,
			mode:     Mode{Persist: ptr("direct"), Choice: ptr("global")},
			want:     map[string][]string{},
		},
		{
			name:     "режим под замком провайдера — группы нет",
			template: "mode: global\n",
			persist:  `{"mode":"direct"}`,
			mode:     Mode{Persist: ptr("direct"), Choice: ptr("rule"), Locked: true},
			want:     map[string][]string{},
		},
		{
			name:     "режима нет в шаблоне — группы нет",
			template: "log-level: info\n",
			persist:  `{"mode":"direct"}`,
			mode:     Mode{Persist: ptr("direct"), Choice: ptr("global")},
			want:     map[string][]string{},
		},
	})
}

func TestDifferencesValues(t *testing.T) {
	values, err := Differences(
		[]byte("mode: rule\nlog-level: info\nmixed-port: 0\n"),
		`{"log-level":"debug","mode":"direct","mixed-port":7891}`,
		Mode{Persist: ptr("direct"), Choice: ptr("global")},
	)
	if err != nil {
		t.Fatal(err)
	}

	want := []Group{
		{ID: GroupMode, Lines: []Line{{Key: modeKey, Now: `"global"`, Becomes: `"rule"`, Reset: []string{ModeChoice, modeKey}}}},
	}

	if !reflect.DeepEqual(values.Groups, want) {
		t.Fatalf("получено %+v", values.Groups)
	}
}

func TestDifferencesRejectsBrokenInput(t *testing.T) {
	if _, err := Differences([]byte("log-level: [\n"), `{}`, Mode{}); err == nil {
		t.Fatal("битый шаблон выдан за «всё как в подписке»")
	}

	if _, err := Differences([]byte("log-level: info\n"), `{`, Mode{}); err == nil {
		t.Fatal("битые настройки выданы за «всё как в подписке»")
	}
}
