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
			name:     "ключа нет в шаблоне — строки нет",
			template: "proxies: []\n",
			persist:  `{"log-level":"debug","mixed-port":7891,"tcp-concurrent":true}`,
			want:     map[string][]string{},
		},
		{
			name:     "своё значение совпадает с шаблоном — строки нет",
			template: "log-level: info\nmixed-port: 7890\nallow-lan: false\n",
			persist:  `{"log-level":"info","mixed-port":7890,"allow-lan":false}`,
			want:     map[string][]string{},
		},
		{
			name:     "своего значения нет — строки нет",
			template: "log-level: warning\nmixed-port: 7890\n",
			persist:  `{}`,
			want:     map[string][]string{},
		},
		{
			name:     "отличается — строка в своей группе",
			template: "log-level: info\nunified-delay: true\nmixed-port: 7890\nipv6: true\ngeodata-mode: true\n",
			persist:  `{"log-level":"debug","unified-delay":false,"mixed-port":7891,"ipv6":false,"geodata-mode":false,"secret":"x"}`,
			want: map[string][]string{
				GroupLog:      {"log-level", "unified-delay"},
				GroupPorts:    {"mixed-port", "ipv6"},
				GroupFeatures: {"geodata-mode"},
			},
		},
		{
			name:     "порт 0 из шаблона ядро заменяет своим — сравнивается с ним",
			template: "mixed-port: 0\n",
			persist:  `{"mixed-port":7890}`,
			want:     map[string][]string{},
		},
		{
			name:     "порт 0 из шаблона при своём http-порте остаётся 0",
			template: "mixed-port: 0\n",
			persist:  `{"mixed-port":0,"port":8080}`,
			want:     map[string][]string{},
		},
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
		{ID: GroupLog, Lines: []Line{{Key: "log-level", Now: `"debug"`, Becomes: `"info"`, Reset: []string{"log-level"}}}},
		{ID: GroupPorts, Lines: []Line{{Key: "mixed-port", Now: `7891`, Becomes: `7890`, Reset: []string{"mixed-port"}}}},
	}

	if !reflect.DeepEqual(values.Groups, want) {
		t.Fatalf("получено %+v", values.Groups)
	}
}

func TestDifferencesBecomesKeepsOtherOwnValues(t *testing.T) {
	values, err := Differences([]byte("mixed-port: 0\n"), `{"mixed-port":7891,"port":8080}`, Mode{})
	if err != nil {
		t.Fatal(err)
	}

	want := []Group{{ID: GroupPorts, Lines: []Line{{Key: "mixed-port", Now: `7891`, Becomes: `0`, Reset: []string{"mixed-port"}}}}}

	if !reflect.DeepEqual(values.Groups, want) {
		t.Fatalf("получено %+v", values.Groups)
	}
}

func TestDifferencesStack(t *testing.T) {
	cases := []struct {
		template string
		want     string
	}{
		{"tun:\n  enable: true\n  stack: system\n", "system"},
		{"tun:\n  enable: true\n", ""},
		{"tun:\n  enable: false\n  stack: system\n", ""},
		{"listeners:\n  - name: t\n    type: tun\n    stack: mixed\n", "mixed"},
		{"tun:\n  enable: true\nlisteners:\n  - name: t\n    type: tun\n    stack: system\n", "mips"},
		{"tun:\n  enable: true\nlisteners:\n  - name: t\n    type: tun\n", ""},
	}

	for _, c := range cases {
		values, err := Differences([]byte(c.template), `{}`, Mode{})
		if err != nil {
			t.Fatal(err)
		}

		if values.Stack != c.want {
			t.Errorf("%q: стек %q, ожидался %q", c.template, values.Stack, c.want)
		}
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
