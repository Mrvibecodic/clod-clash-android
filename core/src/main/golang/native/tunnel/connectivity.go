package tunnel

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

const (
	healthCheckConcurrency = 10

	healthCheckProbeTimeout = 5 * time.Second

	healthCheckTotalTimeout = 45 * time.Second
)

type probeResult struct {
	done   chan struct{}
	probed bool
	delay  uint16
	err    error
}

var (
	probeMu       sync.Mutex
	probeRoot     context.Context
	probeAbort    context.CancelFunc
	probeInflight = map[string]*probeResult{}
	probeSlots    = make(chan struct{}, healthCheckConcurrency)
)

func probeContext() context.Context {
	probeMu.Lock()
	defer probeMu.Unlock()

	if probeRoot == nil || probeRoot.Err() != nil {
		probeRoot, probeAbort = context.WithCancel(context.Background())
	}

	return probeRoot
}

func CancelHealthChecks() {
	probeMu.Lock()
	defer probeMu.Unlock()

	if probeAbort != nil {
		probeAbort()
	}
}

func healthCheckBudget(count int) time.Duration {
	need := time.Duration(count/healthCheckConcurrency+2) * healthCheckProbeTimeout

	if need < healthCheckTotalTimeout {
		return healthCheckTotalTimeout
	}

	return need
}

func probeProxy(ctx context.Context, px C.Proxy, url string, statusKey string, expected utils.IntRanges[uint16]) (uint16, bool, error) {
	key := px.Name() + "|" + url + "|" + statusKey

	for {
		probeMu.Lock()

		if shared, ok := probeInflight[key]; ok {
			probeMu.Unlock()

			select {
			case <-shared.done:
				if !shared.probed && ctx.Err() == nil {
					continue
				}

				return shared.delay, shared.probed, shared.err
			case <-ctx.Done():
				return 0, false, ctx.Err()
			}
		}

		own := &probeResult{done: make(chan struct{})}
		probeInflight[key] = own

		probeMu.Unlock()

		defer func() {
			probeMu.Lock()
			delete(probeInflight, key)
			probeMu.Unlock()

			close(own.done)
		}()

		select {
		case probeSlots <- struct{}{}:
			defer func() { <-probeSlots }()
		case <-ctx.Done():
			own.err = ctx.Err()

			return 0, false, own.err
		}

		probe, cancel := context.WithTimeout(probeContext(), healthCheckProbeTimeout)
		defer cancel()

		own.probed = true
		own.delay, own.err = px.URLTest(probe, url, expected)

		return own.delay, true, own.err
	}
}

func groupCheckOptions(g outboundgroup.ProxyGroup) (string, string, utils.IntRanges[uint16]) {
	url := ""
	status := ""

	if data, err := json.Marshal(g); err == nil {
		var meta map[string]any

		if json.Unmarshal(data, &meta) == nil {
			if v, ok := meta["testUrl"].(string); ok {
				url = strings.TrimSpace(v)
			}

			if v, ok := meta["expectedStatus"].(string); ok {
				status = strings.TrimSpace(v)
			}
		}
	}

	if url == "" {
		for _, pr := range g.Providers() {
			if u := strings.TrimSpace(pr.HealthCheckURL()); u != "" {
				url = u

				break
			}
		}
	}

	if url == "" {
		url = C.DefaultTestURL
	}

	if status == "" || status == "*" {
		return url, "", nil
	}

	expected, err := utils.NewUnsignedRanges[uint16](status)
	if err != nil {
		log.Warnln("Health check: bad expected status `%s`: %s", status, err.Error())

		return url, "", nil
	}

	return url, status, expected
}

func GroupTestURL(g outboundgroup.ProxyGroup) string {
	url, _, _ := groupCheckOptions(g)

	return url
}

func HealthCheck(name string) {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	proxies := g.Proxies()
	if len(proxies) == 0 {
		log.Warnln("Request health check for `%s`: group is empty", name)

		return
	}

	url, statusKey, expectedStatus := groupCheckOptions(g)

	log.Infoln("Health check `%s`: %d proxies via %s", name, len(proxies), url)

	ctx, cancel := context.WithTimeout(probeContext(), healthCheckBudget(len(proxies)))
	defer cancel()

	var checked, alive atomic.Int32

	wg := &sync.WaitGroup{}

	for _, proxy := range proxies {
		wg.Add(1)

		go func(px C.Proxy) {
			defer wg.Done()

			_, done, err := probeProxy(ctx, px, url, statusKey, expectedStatus)

			if !done {
				return
			}

			checked.Add(1)

			if err != nil {
				log.Debugln("Health check `%s`: %s failed: %s", name, px.Name(), err.Error())

				return
			}

			alive.Add(1)
		}(proxy)
	}

	wg.Wait()

	log.Infoln(
		"Health check `%s`: %d alive of %d checked, %d of %d not checked",
		name, alive.Load(), checked.Load(), int32(len(proxies))-checked.Load(), len(proxies),
	)
}

func ProbeCurrentNodes() {
	proxies := tunnel.Proxies()
	seen := make(map[string]bool, len(proxies))

	ctx, cancel := context.WithTimeout(probeContext(), healthCheckTotalTimeout)

	var pending atomic.Int32

	pending.Add(1)

	release := func() {
		if pending.Add(-1) == 0 {
			cancel()
		}
	}

	for _, p := range proxies {
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}

		now := g.Now()
		if now == "" {
			continue
		}

		target := proxies[now]
		if target == nil {
			continue
		}

		url, statusKey, expectedStatus := groupCheckOptions(g)
		if url == "" {
			continue
		}

		key := now + "|" + url
		if seen[key] {
			continue
		}

		seen[key] = true

		pending.Add(1)

		go func(px C.Proxy, url string, statusKey string, expected utils.IntRanges[uint16]) {
			defer release()

			delay, done, err := probeProxy(ctx, px, url, statusKey, expected)
			if !done || err != nil {
				log.Infoln("Probe after network change: %s failed", px.Name())

				return
			}

			log.Infoln("Probe after network change: %s is alive, %d ms", px.Name(), delay)
		}(target, url, statusKey, expectedStatus)
	}

	release()
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}
