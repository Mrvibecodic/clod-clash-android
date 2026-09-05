package tunnel

import (
	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/transport/anytls"
	"github.com/metacubex/mihomo/transport/tuic"
)

// resetProxyTransports looks ResetNetwork up at runtime, so a half applied
// mux_reset_on_network_change patch would compile and silently do nothing.
// These assertions turn every missing hunk into a build failure.
type resetsNetwork interface {
	ResetNetwork()
}

var (
	_ resetsNetwork = (*outbound.AnyTLS)(nil)
	_ resetsNetwork = (*outbound.Hysteria2)(nil)
	_ resetsNetwork = (*outbound.SingMux)(nil)
	_ resetsNetwork = (*outbound.Ssh)(nil)
	_ resetsNetwork = (*outbound.Trojan)(nil)
	_ resetsNetwork = (*outbound.Tuic)(nil)
	_ resetsNetwork = (*outbound.Vless)(nil)
	_ resetsNetwork = (*outbound.Vmess)(nil)
	_ resetsNetwork = (*anytls.Client)(nil)
	_ resetsNetwork = (*tuic.PoolClient)(nil)
)
