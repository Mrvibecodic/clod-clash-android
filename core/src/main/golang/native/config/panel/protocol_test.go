package panel

import (
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

// Те же подписи, что у прослойки (reports_view.php, rep_proto_label), с
// транспортом и Reality так, как их понимает ядро.
func TestLabel(t *testing.T) {
	key := Transport{TLS: true, RealityKey: true}

	for _, row := range []struct {
		kind string
		t    Transport
		want string
	}{
		{"vless", Transport{}, "VLESS RAW (TCP)"},
		{"vless", Transport{Network: "tcp"}, "VLESS RAW (TCP)"},
		{"vless", key, "VLESS RAW (TCP) · Reality"},
		{"vless", Transport{Network: "tcp", TLS: true}, "VLESS RAW (TCP) · TLS"},
		// Reality без TLS ядро не включает.
		{"vless", Transport{RealityKey: true}, "VLESS RAW (TCP)"},
		{"vless", Transport{Network: "ws", TLS: true}, "VLESS WebSocket · TLS"},
		// У ws Reality нет — только TLS.
		{"vless", Transport{Network: "ws", TLS: true, RealityKey: true}, "VLESS WebSocket · TLS"},
		{"vless", Transport{Network: "ws", HTTPUpgrade: true}, "VLESS HTTPUpgrade"},
		{"vless", Transport{Network: "xhttp", TLS: true, RealityKey: true}, "VLESS XHTTP · Reality"},
		{"vless", Transport{Network: "grpc", TLS: true, RealityKey: true}, "VLESS gRPC · Reality"},
		{"vless", Transport{Network: "h2", TLS: true}, "VLESS HTTP/2 · TLS"},
		// http у ядра — HTTP/1.1-обёртка поверх TCP, как RAW (TCP) с headerType=http.
		{"vless", Transport{Network: "http"}, "VLESS RAW (TCP)"},
		// Сети, которых ядро у протокола не знает, и другой регистр — TCP.
		{"vless", Transport{Network: "httpupgrade"}, "VLESS RAW (TCP)"},
		{"vless", Transport{Network: "splithttp"}, "VLESS RAW (TCP)"},
		{"vless", Transport{Network: "kcp"}, "VLESS RAW (TCP)"},
		{"vless", Transport{Network: "WS"}, "VLESS RAW (TCP)"},
		{"vmess", Transport{Network: "grpc", TLS: true}, "VMess gRPC · TLS"},
		{"vmess", Transport{Network: "kcp"}, "VMess mKCP"},
		{"vmess", Transport{Network: "mkcp"}, "VMess mKCP"},
		{"vmess", Transport{Network: "xhttp"}, "VMess RAW (TCP)"},
		{"vmess", Transport{Network: "http"}, "VMess RAW (TCP)"},
		// TLSMirror у VMess — на любой сети, ws тоже; без TLS ядро его не включает.
		{"vmess", Transport{Network: "ws", TLS: true, TLSMirrorKey: true}, "VMess WebSocket · TLSMirror"},
		{"vmess", Transport{Network: "grpc", TLS: true, TLSMirrorKey: true}, "VMess gRPC · TLSMirror"},
		{"vmess", Transport{TLS: true, TLSMirrorKey: true}, "VMess RAW (TCP) · TLSMirror"},
		{"vmess", Transport{TLSMirrorKey: true}, "VMess RAW (TCP)"},
		{"vless", Transport{TLS: true, TLSMirrorKey: true}, "VLESS RAW (TCP) · TLS"},
		{"trojan", Transport{}, "Trojan RAW (TCP) · TLS"},
		{"trojan", Transport{RealityKey: true}, "Trojan RAW (TCP) · Reality"},
		{"trojan", Transport{Network: "grpc", RealityKey: true}, "Trojan gRPC · Reality"},
		{"trojan", Transport{Network: "ws", RealityKey: true}, "Trojan WebSocket · TLS"},
		{"trojan", Transport{Network: "h2"}, "Trojan RAW (TCP) · TLS"},
		{"ss", Transport{}, "Shadowsocks"},
		// Тип ядро сравнивает с учётом регистра; незнакомый — без подписи,
		// остаётся тип ядра.
		{"Shadowsocks", Transport{Network: "ws"}, ""},
		{"VLESS", Transport{}, ""},
		{"hysteria2", Transport{TLS: true, RealityKey: true}, "Hysteria2 (UDP)"},
		{"hy2", Transport{}, ""},
		{"hysteria", Transport{}, "Hysteria (UDP)"},
		{"tuic", Transport{}, "TUIC (UDP)"},
		{"wireguard", Transport{}, "WireGuard (UDP)"},
		{"socks5", Transport{}, "SOCKS5"},
		{"socks5", Transport{TLS: true}, "SOCKS5 · TLS"},
		{"http", Transport{}, "HTTP"},
		{"anytls", Transport{}, "AnyTLS"},
		{"ssh", Transport{}, "SSH"},
		{"mieru", Transport{}, "Mieru"},
		{"snell", Transport{}, ""},
		{"sudoku", Transport{}, ""},
		{"", Transport{}, ""},
	} {
		if got := Label(row.kind, row.t); got != row.want {
			t.Fatalf("%s %+v: %q, ждали %q", row.kind, row.t, got, row.want)
		}
	}
}

func TestTransportOfReadsTheConfigLikeTheCore(t *testing.T) {
	got := TransportOf(map[string]any{
		"network":      "ws",
		"tls":          1,
		"reality-opts": map[string]any{"public-key": "k"},
		"ws-opts":      map[string]any{"v2ray-http-upgrade": true},
	})
	if got != (Transport{Network: "ws", TLS: true, RealityKey: true, HTTPUpgrade: true}) {
		t.Fatalf("%+v", got)
	}

	// Строку ядро флагом не считает (такой узел оно не разберёт вовсе).
	if got := TransportOf(map[string]any{"tls": "true", "reality-opts": map[string]any{}}); got != (Transport{}) {
		t.Fatalf("%+v", got)
	}

	// Ключи — без учёта регистра и с «_» вместо «-», как у ядра.
	got = TransportOf(map[string]any{
		"Network":        "ws",
		"TLS":            true,
		"reality_opts":   map[string]any{"Public_Key": "k"},
		"WS_OPTS":        map[string]any{"v2ray_http_upgrade": 1},
		"TLSMirror-Opts": map[string]any{"primary_key": "m"},
	})
	if got != (Transport{Network: "ws", TLS: true, RealityKey: true, TLSMirrorKey: true, HTTPUpgrade: true}) {
		t.Fatalf("%+v", got)
	}
}

func TestKindOfCoreNodes(t *testing.T) {
	for at, want := range map[C.AdapterType]string{
		C.Vless: "vless", C.Shadowsocks: "ss", C.Socks5: "socks5", C.Hysteria2: "hysteria2",
		C.Snell: "", C.ShadowsocksR: "", C.Selector: "", C.Direct: "",
	} {
		if got := KindOf(at); got != want {
			t.Fatalf("%s: %q, ждали %q", at, got, want)
		}
	}
}

func TestProtocolsCoverTheSubscriptionsOwnNodes(t *testing.T) {
	got := Protocols([]map[string]any{
		{"name": "a", "type": "vless", "tls": true, "reality-opts": map[string]any{"public-key": "k"}},
		{"name": "b", "type": "trojan", "network": "grpc"},
		{"name": "", "type": "ss"},
		{"name": "c"},
		{"Name": "d", "type": "vmess", "Network": "grpc", "TLS": true},
		{"name": "e", "type": "snell"},
	})
	if len(got) != 3 || got["a"] != "VLESS RAW (TCP) · Reality" || got["b"] != "Trojan gRPC · TLS" || got["d"] != "VMess gRPC · TLS" {
		t.Fatalf("%v", got)
	}
	if Protocols(nil) != nil {
		t.Fatal("без узлов подписей нет")
	}
}
