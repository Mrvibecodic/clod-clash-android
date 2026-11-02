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
