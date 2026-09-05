package tunnel

import (
	"cfa/native/config"

	"github.com/metacubex/mihomo/component/iface"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func OnNetworkChanged(closeConnections bool) {
	if !config.IsLoaded() {
		log.Infoln("Network changed: config not loaded, reset=%t skipped", closeConnections)

		return
	}

	NoteNetworkChange()

	CancelHealthChecks()

	iface.FlushCache()

	resolver.ResetConnection()

	resolver.ClearCache()

	if !closeConnections {
		log.Infoln("Network changed: interface cache, DNS cache and DNS connections reset")

		return
	}

	resetProxyTransports()

	closed := 0

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()

		closed++

		return true
	})

	log.Infoln("Network changed: interface cache, DNS cache and DNS connections reset, %d connection(s) closed", closed)
}

func resetProxyTransports() {
	seen := map[C.ProxyAdapter]struct{}{}

	reset := 0

	resetOne := func(p C.Proxy) {
		a := p.Adapter()

		if _, done := seen[a]; done {
			return
		}

		seen[a] = struct{}{}

		if r, ok := a.(interface{ ResetNetwork() }); ok {
			r.ResetNetwork()

			reset++
		}
	}

	for _, p := range tunnel.Proxies() {
		resetOne(p)
	}

	for _, pd := range tunnel.Providers() {
		for _, p := range pd.Proxies() {
			resetOne(p)
		}
	}

	if reset > 0 {
		log.Infoln("Network changed: %d proxy transport(s) reset", reset)
	}
}
