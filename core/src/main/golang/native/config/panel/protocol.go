package panel

import (
	"github.com/metacubex/mihomo/common/structure"
	C "github.com/metacubex/mihomo/constant"
)

// Подпись протокола узла — как у прослойки во вкладке «Статистика»
// (reports_view.php, rep_proto_label): имя протокола, у VLESS, VMess и Trojan
// транспорт, защита Reality, TLSMirror или TLS, у протоколов поверх UDP —
// «(UDP)». Всё — так, как это понимает ядро (adapter.ParseProxy и
// adapter/outbound): тип и сеть сравниваются с учётом регистра, незнакомую
// протоколу сеть ядро везёт как TCP, ключи узла — без учёта регистра и с «_»
// вместо «-», значения — с мягкими типами.

// Протоколы по типу из конфига; у остальных подписи нет — остаётся тип ядра.
var protocolNames = map[string]string{
	"vless": "VLESS", "vmess": "VMess", "trojan": "Trojan", "ss": "Shadowsocks",
	"hysteria2": "Hysteria2", "hysteria": "Hysteria", "tuic": "TUIC", "wireguard": "WireGuard",
	"socks5": "SOCKS5", "http": "HTTP", "anytls": "AnyTLS", "ssh": "SSH", "mieru": "Mieru",
}

// Тип из конфига по типу узла ядра — для узлов, разобранных ядром.
var kinds = map[C.AdapterType]string{
	C.Vless: "vless", C.Vmess: "vmess", C.Trojan: "trojan", C.Shadowsocks: "ss",
	C.Hysteria2: "hysteria2", C.Hysteria: "hysteria", C.Tuic: "tuic", C.WireGuard: "wireguard",
	C.Socks5: "socks5", C.Http: "http", C.AnyTLS: "anytls", C.Ssh: "ssh", C.Mieru: "mieru",
}

// Сети, которые ядро различает у протокола; остальные — RAW (TCP).
var transportNames = map[string]map[string]string{
	"vless":  {"ws": "WebSocket", "h2": "HTTP/2", "grpc": "gRPC", "xhttp": "XHTTP"},
	"vmess":  {"ws": "WebSocket", "h2": "HTTP/2", "grpc": "gRPC", "mkcp": "mKCP", "kcp": "mKCP", "mekya": "MEKYA"},
	"trojan": {"ws": "WebSocket", "grpc": "gRPC"},
}

// Transport — из чего складывается подпись: то же, что ядро хранит у узла
// (adapter.Node).
type Transport struct {
	Network      string
	TLS          bool
	RealityKey   bool
	TLSMirrorKey bool
	HTTPUpgrade  bool
}

// KindOf — тип из конфига у узла ядра типа t; пусто — подписи нет.
func KindOf(t C.AdapterType) string {
	return kinds[t]
}

// Label — подпись узла типа kind (как в конфиге); пусто — протокол без
// подписи.
func Label(kind string, t Transport) string {
	out, ok := protocolNames[kind]
	if !ok {
		return ""
	}

	switch kind {
	case "hysteria2", "hysteria", "tuic", "wireguard":
		return out + " (UDP)"
	}

	networks, layered := transportNames[kind]
	if layered {
		name, ok := networks[t.Network]
		switch {
		case !ok:
			name = "RAW (TCP)"
		case t.Network == "ws" && t.HTTPUpgrade:
			name = "HTTPUpgrade"
		}
		out += " " + name
	}

	tls := t.TLS || kind == "trojan"

	switch {
	// TLSMirror у VMess ядро ведёт на любой сети, ws тоже
	case kind == "vmess" && tls && t.TLSMirrorKey:
		out += " · TLSMirror"
	case layered && tls && t.RealityKey && t.Network != "ws":
		out += " · Reality"
	case tls:
		out += " · TLS"
	}

	return out
}

// proxyOption — поля узла из конфига, нужные клиенту, разобранные так же, как
// их разбирает adapter.ParseProxy.
type proxyOption struct {
	Name        string `proxy:"name,omitempty"`
	Network     string `proxy:"network,omitempty"`
	TLS         bool   `proxy:"tls,omitempty"`
	DialerProxy string `proxy:"dialer-proxy,omitempty"`
	RealityOpts struct {
		PublicKey string `proxy:"public-key,omitempty"`
	} `proxy:"reality-opts,omitempty"`
	TLSMirrorOpts struct {
		PrimaryKey string `proxy:"primary-key,omitempty"`
	} `proxy:"tlsmirror-opts,omitempty"`
	WSOpts struct {
		V2rayHttpUpgrade bool `proxy:"v2ray-http-upgrade,omitempty"`
	} `proxy:"ws-opts,omitempty"`
}

// Поле не того типа остаётся пустым, остальные читаются; узел, который ядро
// не примет, не загрузит и подписка.
func decodeProxy(proxy map[string]any) proxyOption {
	var opt proxyOption
	_ = structure.NewDecoder(structure.Option{TagName: "proxy", WeaklyTypedInput: true, KeyReplacer: structure.DefaultKeyReplacer}).Decode(proxy, &opt)

	return opt
}

// TransportOf — то же, что ядро запоминает у узла при разборе (adapter.nodeOf).
func TransportOf(proxy map[string]any) Transport {
	opt := decodeProxy(proxy)

	return Transport{
		Network:      opt.Network,
		TLS:          opt.TLS,
		RealityKey:   opt.RealityOpts.PublicKey != "",
		TLSMirrorKey: opt.TLSMirrorOpts.PrimaryKey != "",
		HTTPUpgrade:  opt.WSOpts.V2rayHttpUpgrade,
	}
}

// Protocols — подписи узлов из proxies подписки — для списка без туннеля: в
// нём только они (узлы провайдеров видны лишь в ядре, подпись им ставит ядро).
// Имена в proxies у ядра уникальны; nil — подписывать нечего.
func Protocols(own []map[string]any) map[string]string {
	out := map[string]string{}

	for _, proxy := range own {
		// Тип ядро читает точно по ключу «type» (adapter.ParseProxy)
		kind, _ := proxy["type"].(string)
		name := decodeProxy(proxy).Name
		if label := Label(kind, TransportOf(proxy)); name != "" && label != "" {
			out[name] = label
		}
	}

	if len(out) == 0 {
		return nil
	}

	return out
}
