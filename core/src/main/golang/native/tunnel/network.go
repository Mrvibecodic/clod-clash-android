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

	closed := CloseAllConnections()

	log.Infoln("Network changed: interface cache, DNS cache and DNS connections reset, %d connection(s) closed", closed)
}

// eachProxy — все исходящие ядра: из его списка и из провайдеров (узлов
// провайдеров в списке нет). Один и тот же может встретиться дважды — повторы
// отсеивает вызывающий, по своему ключу
func eachProxy(visit func(C.Proxy)) {
	for _, p := range tunnel.Proxies() {
		visit(p)
	}

	for _, pd := range tunnel.Providers() {
		for _, p := range pd.Proxies() {
			visit(p)
		}
	}
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

	eachProxy(resetOne)

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
