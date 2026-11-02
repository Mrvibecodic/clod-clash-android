package overlay

import (
	"reflect"
	"testing"
)

func TestDifferencesDns(t *testing.T) {
	runDifferences(t, []differencesCase{
		{
			name:     "в шаблоне нет DNS — строк DNS нет",
			template: "log-level: info\n",
			persist:  `{"dns":{"nameserver":["https://dns.example.com/dns-query"],"enable":false}}`,
			want:     map[string][]string{},
		},
		{
			name:     "DNS — по каждому ключу отдельно",
			template: "dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\n  ipv6: false\n",
			persist:  `{"dns":{"nameserver":["https://other.example.com/dns-query"],"fallback":["https://fallback.example.com/dns-query"],"ipv6":false}}`,
			want:     map[string][]string{GroupDNS: {"dns.nameserver"}},
		},
		{
			name:     "пустой список серверов ядро не берёт — разницы нет",
			template: "dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\n",
			persist:  `{"dns":{"nameserver":[]}}`,
			want:     map[string][]string{},
		},
		{
			name:     "«Включить принудительно» поверх выключенного DNS шаблона",
			template: "dns:\n  enable: false\n  nameserver: [https://dns.example.com/dns-query]\n",
			persist:  `{"dns":{"enable":true,"nameserver":["https://other.example.com/dns-query"]}}`,
			want:     map[string][]string{GroupDNS: {"dns.enable", "dns.nameserver"}},
		},
		{
			name:     "«Использовать встроенный» — свои серверы ядро не берёт",
			template: "dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\n",
			persist:  `{"dns":{"enable":false}}`,
			want:     map[string][]string{GroupDNS: {"dns.enable"}},
		},
		{
			name:     "у ключа DNS без enable в шаблоне строка тоже есть",
			template: "dns:\n  nameserver: [https://dns.example.com/dns-query]\n",
			persist:  `{"dns":{"enable":true}}`,
			want:     map[string][]string{GroupDNS: {"dns.enable"}},
		},
		{
			name:     "системный DNS дописывается к обоим — разницы нет",
			template: "dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\nclash-for-android:\n  append-system-dns: true\n",
			persist:  `{"dns":{"nameserver":["https://dns.example.com/dns-query","system://"]}}`,
			want:     map[string][]string{},
		},
		{
			name:     "пустую запись hosts ядро не берёт — разницы нет",
			template: "hosts:\n  a.example.com: 192.0.2.1\n  b.example.com: ~\n",
			persist:  `{"hosts":{"a.example.com":"192.0.2.1"}}`,
			want:     map[string][]string{},
		},
		{
			name:     "hosts сливаются с шаблоном: те же записи — разницы нет",
			template: "hosts:\n  a.example.com: [192.0.2.1]\n",
			persist:  `{"hosts":{"a.example.com":"192.0.2.1"}}`,
			want:     map[string][]string{},
		},
		{
			name:     "hosts со своей записью",
			template: "hosts:\n  a.example.com: 192.0.2.1\n",
			persist:  `{"hosts":{"b.example.com":"192.0.2.2"}}`,
			want:     map[string][]string{GroupDNS: {"hosts"}},
		},
		{
			name:     "политика: строка и список из одной строки равны",
			template: "dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\n  nameserver-policy:\n    '+.example.com': [https://dns.example.com/dns-query]\n",
			persist:  `{"dns":{"nameserver-policy":{"+.example.com":"https://dns.example.com/dns-query"}}}`,
			want:     map[string][]string{},
		},
	})
}

func TestDifferencesDnsShowsWhatTheCoreGets(t *testing.T) {
	values, err := Differences(
		[]byte("dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\n"),
		`{"dns":{"enable":false,"nameserver":["https://other.example.com/dns-query"]}}`,
		Mode{},
	)
	if err != nil {
		t.Fatal(err)
	}

	want := []Line{
		{Key: "dns.enable", Now: `false`, Becomes: `true`, Reset: []string{"dns.enable"}},
		{Key: "dns.nameserver", Now: `["1.0.0.1","8.8.4.4","9.9.9.10","system://"]`, Becomes: `["https://dns.example.com/dns-query"]`, Reset: []string{"dns.nameserver"}},
	}

	if len(values.Groups) != 1 || !reflect.DeepEqual(values.Groups[0].Lines, want) {
		t.Fatalf("получено %+v", values.Groups)
	}
}

func TestDifferencesDnsBecomesKeepsSystemDns(t *testing.T) {
	values, err := Differences(
		[]byte("dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\n"),
		`{"dns":{"nameserver":["https://other.example.com/dns-query"]},"clash-for-android":{"append-system-dns":true}}`,
		Mode{},
	)
	if err != nil {
		t.Fatal(err)
	}

	want := []Line{{
		Key:     "dns.nameserver",
		Now:     `["https://other.example.com/dns-query","system://"]`,
		Becomes: `["https://dns.example.com/dns-query","system://"]`,
		Reset:   []string{"dns.nameserver"},
	}}

	if len(values.Groups) != 1 || !reflect.DeepEqual(values.Groups[0].Lines, want) {
		t.Fatalf("получено %+v", values.Groups)
	}
}
