package overlay

import (
	"encoding/json"
	"reflect"
	"testing"
)

func decode(t *testing.T, content string) map[string]any {
	t.Helper()

	var result map[string]any
	if err := json.Unmarshal([]byte(content), &result); err != nil {
		t.Fatalf("%q: %v", content, err)
	}

	return result
}

func TestClear(t *testing.T) {
	cases := []struct {
		name    string
		persist string
		keys    []string
		want    string
	}{
		{"верхний ключ", `{"log-level":"debug","mixed-port":7891}`, []string{"log-level"}, `{"mixed-port":7891}`},
		{
			"вложенный ключ, соседи остаются",
			`{"dns":{"nameserver":["https://dns.example.com/dns-query"],"listen":"0.0.0.0:53"}}`,
			[]string{"dns.nameserver"},
			`{"dns":{"listen":"0.0.0.0:53"}}`,
		},
		{
			"запись протокола целиком, опустевшие разделы снимаются",
			`{"sniffer":{"sniff":{"TLS":{"ports":["8443"],"override-destination":false}}},"ipv6":false}`,
			[]string{"sniffer.sniff.TLS"},
			`{"ipv6":false}`,
		},
		{"ключа нет — ничего не меняется", `{"ipv6":false,"dns":null}`, []string{"dns.nameserver", "log-level"}, `{"ipv6":false,"dns":null}`},
		{"пустой файл остаётся объектом", `null`, []string{"log-level"}, `{}`},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := Clear(c.persist, c.keys)
			if err != nil {
				t.Fatal(err)
			}

			if !reflect.DeepEqual(decode(t, got), decode(t, c.want)) {
				t.Fatalf("получено %s, ожидалось %s", got, c.want)
			}
		})
	}
}

func TestClearRejectsBrokenSettings(t *testing.T) {
	if _, err := Clear(`{`, []string{"log-level"}); err == nil {
		t.Fatal("битые настройки переписаны")
	}

	if _, err := Clear(`{"dns":[1]}`, []string{"dns.nameserver"}); err == nil {
		t.Fatal("раздел не объектом выдан за снятый ключ")
	}
}

func TestResettable(t *testing.T) {
	got := Resettable([]string{"log-level", "secret", "external-controller", "clash-for-android.append-system-dns", ModeChoice, "mode", "sniffer.sniff.TLS", "dns.nameserver"})
	want := []string{"log-level", "mode", "sniffer.sniff.TLS", "dns.nameserver"}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("получено %v", got)
	}
}

func TestClearReturnsTemplateValues(t *testing.T) {
	template := []byte("log-level: info\nmixed-port: 7890\ndns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\nsniffer:\n  sniff:\n    TLS:\n      ports: [443]\n")
	persist := `{"log-level":"debug","mixed-port":7891,"dns":{"nameserver":["https://other.example.com/dns-query"]},"sniffer":{"sniff":{"TLS":{"ports":["8443"]}}},"secret":"x"}`

	values, err := Differences(template, persist, Mode{})
	if err != nil {
		t.Fatal(err)
	}

	var keys []string
	for _, group := range values.Groups {
		for _, line := range group.Lines {
			keys = append(keys, line.Reset...)
		}
	}

	cleared, err := Clear(persist, Resettable(keys))
	if err != nil {
		t.Fatal(err)
	}

	after, err := Differences(template, cleared, Mode{})
	if err != nil {
		t.Fatal(err)
	}

	if len(after.Groups) != 0 || len(keys) != 4 {
		t.Fatalf("сняты %v, после снятия %+v", keys, after.Groups)
	}

	if !reflect.DeepEqual(decode(t, cleared), decode(t, `{"secret":"x"}`)) {
		t.Fatalf("снято лишнее или не всё: %s", cleared)
	}
}
