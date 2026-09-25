package sentinel

import (
	"reflect"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/common/yaml"
)

func TestUnspecifiedHostIsSentinel(t *testing.T) {
	for _, host := range []string{"0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0", ""} {
		if !Is(map[string]any{"name": "заглушка", "type": "vless", "server": host, "port": 443}) {
			t.Fatalf("узел с адресом %q должен считаться заглушкой", host)
		}
	}
}

func TestRealProxyIsNotSentinel(t *testing.T) {
	proxy := map[string]any{
		"name":   "Нидерланды 01",
		"type":   "vless",
		"server": "example.org",
		"port":   443,
		"uuid":   "6f1a7c2e-0000-4c5e-9f2a-2b1d3e4f5a6b",
	}

	if Is(proxy) {
		t.Fatal("рабочий узел не должен считаться заглушкой")
	}
}

func TestNilUUIDIsSentinel(t *testing.T) {
	proxy := map[string]any{
		"name":   "заглушка",
		"type":   "vless",
		"server": "example.org",
		"port":   443,
		"uuid":   "00000000-0000-0000-0000-000000000000",
	}

	if !Is(proxy) {
		t.Fatal("нулевой uuid должен считаться заглушкой")
	}
}

func TestDeadPortWithoutCredentialsIsSentinel(t *testing.T) {
	proxy := map[string]any{"name": "заглушка", "type": "ss", "server": "example.org", "port": 0}

	if !Is(proxy) {
		t.Fatal("нулевой порт без пароля должен считаться заглушкой")
	}

	proxy["password"] = "s3cret"

	if Is(proxy) {
		t.Fatal("узел с паролем заглушкой не считается")
	}
}

func TestServerlessTypesAreNeverSentinels(t *testing.T) {
	for _, kind := range []string{"direct", "reject", "reject-drop", "pass", "dns", "DIRECT"} {
		if Is(map[string]any{"name": kind, "type": kind}) {
			t.Fatalf("встроенный тип %q заглушкой не считается", kind)
		}
	}
}

func TestInspectCollectsNamesAndDetectsOnlySentinels(t *testing.T) {
	proxies := []map[string]any{
		{"name": "Подписка истекла", "type": "vless", "server": "0.0.0.0", "port": 443},
		{"name": "Продлите доступ", "type": "vless", "server": "0.0.0.0", "port": 443},
	}

	report := Inspect(proxies)

	if !report.OnlySentinels {
		t.Fatal("конфиг из одних заглушек должен помечаться OnlySentinels")
	}

	if len(report.Names) != 2 {
		t.Fatalf("ожидались два имени, получено %d", len(report.Names))
	}
}

func TestInspectWithOneRealProxy(t *testing.T) {
	proxies := []map[string]any{
		{"name": "Подписка истекла", "type": "vless", "server": "0.0.0.0", "port": 443},
		{"name": "Нидерланды 01", "type": "vless", "server": "example.org", "port": 443, "uuid": "6f1a7c2e-0000-4c5e-9f2a-2b1d3e4f5a6b"},
	}

	report := Inspect(proxies)

	if report.OnlySentinels {
		t.Fatal("при живом узле OnlySentinels должен быть false")
	}

	if len(report.Names) != 1 || report.Names[0] != "Подписка истекла" {
		t.Fatalf("в списке скрываемых должна быть одна заглушка, получено %v", report.Names)
	}
}

func TestInspectIgnoresServerlessTypes(t *testing.T) {
	proxies := []map[string]any{
		{"name": "Подписка истекла", "type": "vless", "server": "0.0.0.0", "port": 443},
		{"name": "Без VPN", "type": "direct"},
		{"name": "DNS-OUT", "type": "dns"},
	}

	report := Inspect(proxies)

	if !report.OnlySentinels {
		t.Fatal("встроенные типы рядом с заглушками не должны считаться живыми узлами")
	}

	if len(report.Names) != 1 || report.Names[0] != "Подписка истекла" {
		t.Fatalf("скрывать надо только заглушку, получено %v", report.Names)
	}

	if report := Inspect([]map[string]any{{"name": "Без VPN", "type": "direct"}}); report.OnlySentinels {
		t.Fatal("конфиг из одного встроенного типа без заглушек не должен помечаться OnlySentinels")
	}
}

func TestInspectCapsRemarks(t *testing.T) {
	proxies := make([]map[string]any, 0, 10)

	for i := 0; i < 10; i++ {
		proxies = append(proxies, map[string]any{"name": "заглушка", "type": "vless", "server": "0.0.0.0"})
	}

	report := Inspect(proxies)

	if len(report.Remarks) != maxReportedRemarks {
		t.Fatalf("в лог уходит не больше %d имён, получено %d", maxReportedRemarks, len(report.Remarks))
	}

	if len(report.Names) != 10 {
		t.Fatalf("скрывать надо все заглушки, получено %d", len(report.Names))
	}
}

func TestInspectEmpty(t *testing.T) {
	report := Inspect(nil)

	if report.OnlySentinels || len(report.Names) != 0 {
		t.Fatal("пустой список узлов не должен давать признак заглушек")
	}
}

func TestDisarmKeepsNamesAndDropsSecrets(t *testing.T) {
	template := []byte(`proxies:
  - name: A
    type: vless
    server: a.example.net
    port: 443
    uuid: 6f1c0f6d-1a2b-4c3d-8e9f-0a1b2c3d4e5f
  - name: B
    type: trojan
    server: b.example.net
    port: 443
    password: secret
proxy-groups:
  - name: VPN
    type: select
    proxies: [A, B]
rules:
  - MATCH,VPN
`)

	disarmed, ok := Disarm(template)
	if !ok {
		t.Fatal("шаблон с узлами должен обезоруживаться")
	}

	if strings.Contains(string(disarmed), "a.example.net") || strings.Contains(string(disarmed), "secret") {
		t.Fatalf("адреса и ключи остались: %s", disarmed)
	}

	var document map[string]any
	if err := yaml.Unmarshal(disarmed, &document); err != nil {
		t.Fatal(err)
	}

	var original map[string]any
	if err := yaml.Unmarshal(template, &original); err != nil {
		t.Fatal(err)
	}

	for _, group := range document["proxy-groups"].([]any) {
		fields := group.(map[string]any)
		if fields["empty-fallback"] != "REJECT" {
			t.Fatalf("у группы %v пустота не отвергает", fields["name"])
		}
		delete(fields, "empty-fallback")
	}

	for _, key := range []string{"proxy-groups", "rules"} {
		if !reflect.DeepEqual(document[key], original[key]) {
			t.Fatalf("%s изменился: %v", key, document[key])
		}
	}

	proxies := document["proxies"].([]any)
	nodes := make([]map[string]any, 0, len(proxies))
	names := make([]string, 0, len(proxies))
	for _, proxy := range proxies {
		fields := proxy.(map[string]any)
		nodes = append(nodes, fields)
		names = append(names, fields["name"].(string))
		if !Is(fields) {
			t.Fatalf("узел %v не распознан как заглушка", fields)
		}
	}

	if !reflect.DeepEqual(names, []string{"A", "B"}) {
		t.Fatalf("имена = %v", names)
	}

	if report := Inspect(nodes); !report.OnlySentinels {
		t.Fatal("после обезоруживания в конфиге должны остаться одни заглушки")
	}

	again, ok := Disarm(disarmed)
	if !ok || string(again) != string(disarmed) {
		t.Fatal("повторное обезоруживание должно давать тот же конфиг")
	}
}

func TestDisarmKeepsANumericName(t *testing.T) {
	disarmed, ok := Disarm([]byte("proxies:\n  - name: 123\n    type: ss\n    server: a.example.net\n    port: 8388\n    cipher: aes-128-gcm\n    password: secret\nproxy-groups:\n  - name: VPN\n    type: select\n    proxies: [123]\n"))
	if !ok {
		t.Fatal("узел с числовым именем должен обезоруживаться")
	}

	var document map[string]any
	if err := yaml.Unmarshal(disarmed, &document); err != nil {
		t.Fatal(err)
	}

	proxies := document["proxies"].([]any)
	if len(proxies) != 1 || proxies[0].(map[string]any)["name"] != 123 {
		t.Fatalf("имя узла потеряно: %v", proxies)
	}

	if strings.Contains(string(disarmed), "secret") {
		t.Fatal("ключ остался")
	}
}

func TestDisarmWithoutNodes(t *testing.T) {
	for _, config := range []string{"", "proxies: []\n", "rules:\n  - MATCH,DIRECT\n", "<html></html>", "proxies:\n  - just-a-string\n"} {
		if _, ok := Disarm([]byte(config)); ok {
			t.Fatalf("%q обезоруживать нечего", config)
		}
	}
}

func TestDisarmTakesAwayProviders(t *testing.T) {
	template := []byte(`proxies:
  - name: A
    type: vless
    server: a.example.net
    port: 443
    uuid: 6f1c0f6d-1a2b-4c3d-8e9f-0a1b2c3d4e5f
proxy-providers:
  inline:
    type: inline
    payload:
      - name: A
        type: vless
        server: a.example.net
        port: 443
        uuid: 6f1c0f6d-1a2b-4c3d-8e9f-0a1b2c3d4e5f
  remote:
    type: http
    url: https://panel.example.net/nodes
    path: ./providers/remote.yaml
proxy-groups:
  - name: Mixed
    type: select
    use: [inline]
    proxies: [A]
  - name: FromProvider
    type: url-test
    use: [inline, remote]
  - name: Everything
    type: select
    include-all: true
  - name: AllProviders
    type: select
    include-all-providers: true
rules:
  - MATCH,Mixed
`)

	disarmed, ok := Disarm(template)
	if !ok {
		t.Fatal("шаблон с узлами должен обезоруживаться")
	}

	for _, secret := range []string{"a.example.net", "6f1c0f6d", "panel.example.net", "payload", "proxy-providers"} {
		if strings.Contains(string(disarmed), secret) {
			t.Fatalf("осталось %q: %s", secret, disarmed)
		}
	}

	var document map[string]any
	if err := yaml.Unmarshal(disarmed, &document); err != nil {
		t.Fatal(err)
	}

	groups := map[string]map[string]any{}
	for _, group := range document["proxy-groups"].([]any) {
		fields := group.(map[string]any)
		groups[fields["name"].(string)] = fields
	}

	for name, fields := range groups {
		for _, key := range []string{"use", "include-all", "include-all-providers"} {
			if _, present := fields[key]; present {
				t.Fatalf("у группы %s остался %s", name, key)
			}
		}

		if fields["empty-fallback"] != "REJECT" {
			t.Fatalf("группа %s без участников уйдёт в прямое соединение", name)
		}
	}

	expect := map[string][]any{
		"Mixed":        {"A"},
		"FromProvider": {"REJECT"},
		"AllProviders": {"REJECT"},
	}
	for name, members := range expect {
		if !reflect.DeepEqual(groups[name]["proxies"], members) {
			t.Fatalf("группа %s = %v", name, groups[name]["proxies"])
		}
	}

	if groups["Everything"]["include-all-proxies"] != true || groups["Everything"]["proxies"] != nil {
		t.Fatalf("группа Everything = %v", groups["Everything"])
	}

	if !reflect.DeepEqual(document["rules"], []any{"MATCH,Mixed"}) {
		t.Fatalf("правила изменились: %v", document["rules"])
	}

	again, ok := Disarm(disarmed)
	if !ok || string(again) != string(disarmed) {
		t.Fatal("повторное обезоруживание должно давать тот же конфиг")
	}
}

func TestRefusedWithoutNodesRejectsEverything(t *testing.T) {
	providersOnly := "proxy-providers:\n  inline:\n    type: inline\n    payload:\n      - name: A\n        type: ss\n        server: a.example.net\n        port: 8388\n        cipher: aes-128-gcm\n        password: secret\nproxy-groups:\n  - name: VPN\n    type: select\n    use: [inline]\n"

	for _, previous := range []string{"", "<html></html>", providersOnly} {
		if got := string(Refused([]byte(previous))); got != RefusedConfig {
			t.Fatalf("%q: %s", previous, got)
		}
	}

	if got := string(Refused([]byte("proxies:\n  - name: A\n    type: ss\n    server: a.example.net\n    port: 8388\n    cipher: aes-128-gcm\n    password: secret\n"))); strings.Contains(got, "secret") || !strings.Contains(got, "0.0.0.0") {
		t.Fatalf("прежний конфиг не обезоружен: %s", got)
	}
}
