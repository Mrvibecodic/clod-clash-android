package sentinel

import "testing"

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
