package tunnel

import (
	"sync"
	"time"

	"cfa/native/common/safego"
	"cfa/native/config"

	"github.com/metacubex/mihomo/component/iface"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func OnNetworkChanged(closeConnections bool, holdProbes bool) {
	if !config.IsLoaded() {
		log.Infoln("Network changed: config not loaded, reset=%t skipped", closeConnections)

		return
	}

	if holdProbes {
		NoteNetworkChange()
	} else {
		log.Infoln("Network changed: flapping, probes not held")
	}

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

// resetWait — сколько сброс ждёт узлы перед тем, как рвать соединения:
// закрытие сессии узла ждёт идущее рукопожатие (до тайм-аута дозвона), и
// приложения не должны висеть на соединениях прежней сети всё это время
const resetWait = 300 * time.Millisecond

func resetProxyTransports() {
	seen := map[C.ProxyAdapter]struct{}{}

	reset := 0

	wg := &sync.WaitGroup{}

	resetOne := func(p C.Proxy) {
		a := p.Adapter()

		if _, done := seen[a]; done {
			return
		}

		seen[a] = struct{}{}

		if r, ok := a.(interface{ ResetNetwork() }); ok {
			wg.Add(1)

			safego.Go("resetNetwork", func() {
				defer wg.Done()

				r.ResetNetwork()
			})

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

	done := make(chan struct{})

	safego.Go("resetNetworkWait", func() {
		wg.Wait()

		close(done)
	})

	select {
	case <-done:
	case <-time.After(resetWait):
		log.Infoln("Network changed: some proxy transports still closing a handshake in progress")
	}

	if reset > 0 {
		log.Infoln("Network changed: %d proxy transport(s) reset", reset)
	}
}
