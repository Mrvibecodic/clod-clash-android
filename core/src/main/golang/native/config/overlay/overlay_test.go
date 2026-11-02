package overlay

import (
	"reflect"
	"slices"
	"testing"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

func parse(t *testing.T, yaml string) *config.RawConfig {
	t.Helper()

	cfg, err := config.UnmarshalRawConfig([]byte(yaml))
	if err != nil {
		t.Fatal(err)
	}

	return cfg
}

func TestApplyKeepsSubscriptionNameServersOverEmptyList(t *testing.T) {
	cfg := parse(t, "dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\n")

	Apply(cfg, `{"dns":{"nameserver":[]},"log-level":"debug"}`, `{"mode":"global"}`, t.Logf)

	if !reflect.DeepEqual(cfg.DNS.NameServer, []string{"https://dns.example.com/dns-query"}) ||
		cfg.LogLevel.String() != "debug" || cfg.Mode.String() != "global" {
		t.Fatalf("nameserver %v, log-level %s, mode %s", cfg.DNS.NameServer, cfg.LogLevel, cfg.Mode)
	}
}

func TestDefaultMixedPort(t *testing.T) {
	cases := []struct {
		name  string
		yaml  string
		slots []string
		want  int
	}{
		{"нет портов — свой", "{}\n", []string{`{}`, `{}`}, defaultMixedPort},
		{"порт подписки", "mixed-port: 7891\n", nil, 7891},
		{"http-порт подписки", "port: 8080\n", nil, 0},
		{"раздача в сеть", "allow-lan: true\n", nil, 0},
		{"7890 занят", "socks-port: 7890\n", nil, 0},
		{"пусто в «Переопределении»", "{}\n", []string{`{"mixed-port":0}`}, 0},
		{"пусто в сессии", "{}\n", []string{`{}`, `{"mixed-port":0}`}, 0},
	}

	for _, c := range cases {
		cfg := parse(t, c.yaml)

		DefaultMixedPort(cfg, c.slots...)

		if cfg.MixedPort != c.want {
			t.Errorf("%s: %d, ожидалось %d", c.name, cfg.MixedPort, c.want)
		}
	}
}

func TestDnsOfSubscription(t *testing.T) {
	cfg := parse(t, "dns:\n  enable: true\n  nameserver: [https://dns.example.com/dns-query]\n")

	Dns(cfg, t.Logf, `{"dns":{"nameserver":["https://own.example.com/dns-query"]}}`)

	if !reflect.DeepEqual(cfg.DNS.NameServer, []string{"https://dns.example.com/dns-query"}) {
		t.Fatalf("DNS подписки заменён: %v", cfg.DNS.NameServer)
	}
}

func TestDnsBuiltIn(t *testing.T) {
	cases := []struct {
		name  string
		yaml  string
		slots []string
		want  []string
	}{
		{"без DNS в подписке — встроенный и системный", "{}\n", nil, append(append([]string{}, defaultNameServers...), systemNameServer)},
		{"«Использовать встроенный» — свои не берутся", "{}\n", []string{`{"dns":{"enable":false,"nameserver":["https://own.example.com/dns-query"]}}`}, append(append([]string{}, defaultNameServers...), systemNameServer)},
		{"пустой свой список — встроенный", "{}\n", []string{`{"dns":{"nameserver":[]}}`}, append(append([]string{}, defaultNameServers...), systemNameServer)},
		{"свои серверы", "dns:\n  enable: false\n", []string{`{"dns":{"nameserver":["https://own.example.com/dns-query"]}}`}, []string{"https://own.example.com/dns-query", systemNameServer}},
		{"свои без системного", "{}\n", []string{`{"dns":{"nameserver":["https://own.example.com/dns-query"]},"clash-for-android":{"append-system-dns":false}}`}, []string{"https://own.example.com/dns-query"}},
	}

	for _, c := range cases {
		cfg := parse(t, c.yaml)

		Dns(cfg, t.Logf, c.slots...)

		if !cfg.DNS.Enable || cfg.DNS.EnhancedMode != C.DNSFakeIP || !reflect.DeepEqual(cfg.DNS.NameServer, c.want) {
			t.Errorf("%s: enable %v, mode %s, nameserver %v", c.name, cfg.DNS.Enable, cfg.DNS.EnhancedMode, cfg.DNS.NameServer)
		}
	}
}

func TestDnsKeepsBuiltInLists(t *testing.T) {
	nameServers := slices.Clone(defaultNameServers)
	fakeIPFilter := slices.Clone(defaultFakeIPFilter)

	Dns(parse(t, "{}\n"), t.Logf, `{"dns":{"nameserver":["https://own.example.com/dns-query"],"fake-ip-filter":["+.example.com"]}}`)

	if !reflect.DeepEqual(defaultNameServers, nameServers) || !reflect.DeepEqual(defaultFakeIPFilter, fakeIPFilter) {
		t.Fatalf("встроенные списки изменились: %v, %v", defaultNameServers, defaultFakeIPFilter)
	}

	cfg := parse(t, "{}\n")

	Dns(cfg, t.Logf)

	if !reflect.DeepEqual(cfg.DNS.NameServer, append(nameServers, systemNameServer)) {
		t.Fatalf("встроенный DNS после своего: %v", cfg.DNS.NameServer)
	}
}

func TestApplyReplacesSniffEntryOfAnyCase(t *testing.T) {
	cfg := parse(t, "sniffer:\n  sniff:\n    tls:\n      ports: [443]\n    http:\n      ports: [80]\n    HTTP:\n      ports: [8080]\n")

	Apply(cfg, `{"sniffer":{"sniff":{"TLS":{"ports":["8443"]}}}}`, `{}`, t.Logf)

	want := map[string][]string{"TLS": {"8443"}, "HTTP": {"8080"}}
	got := map[string][]string{}

	for name, entry := range cfg.Sniffer.Sniff {
		got[name] = entry.Ports
	}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("получено %v", got)
	}
}

func TestApplyKeepsUnknownSniffName(t *testing.T) {
	cfg := parse(t, "sniffer:\n  sniff:\n    foo:\n      ports: [1]\n")

	Apply(cfg, `{}`, `{}`, t.Logf)

	if _, ok := cfg.Sniffer.Sniff["foo"]; !ok || len(cfg.Sniffer.Sniff) != 1 {
		t.Fatalf("незнакомое имя изменено: %v", cfg.Sniffer.Sniff)
	}
}
