package tunnel

import (
	"github.com/metacubex/mihomo/component/iface"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func OnNetworkChanged(closeConnections bool) {
	iface.FlushCache()

	resolver.ResetConnection()

	resolver.ClearCache()

	if !closeConnections {
		log.Infoln("Network changed: interface cache, DNS cache and DNS connections reset")

		return
	}

	closed := 0

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()

		closed++

		return true
	})

	log.Infoln("Network changed: interface cache, DNS cache and DNS connections reset, %d connection(s) closed", closed)
}
