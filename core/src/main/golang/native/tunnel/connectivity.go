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

func groupCheckOptions(g outboundgroup.ProxyGroup) (string, utils.IntRanges[uint16]) {
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
		return url, nil
	}

	expected, err := utils.NewUnsignedRanges[uint16](status)
	if err != nil {
		log.Warnln("Health check: bad expected status `%s`: %s", status, err.Error())

		return url, nil
	}

	return url, expected
}

func GroupTestURL(g outboundgroup.ProxyGroup) string {
	url, _ := groupCheckOptions(g)

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

	url, expectedStatus := groupCheckOptions(g)

	log.Infoln("Health check `%s`: %d proxies via %s", name, len(proxies), url)

	ctx, cancel := context.WithTimeout(context.Background(), healthCheckTotalTimeout)
	defer cancel()

	var checked, alive atomic.Int32

	wg := &sync.WaitGroup{}
	sem := make(chan struct{}, healthCheckConcurrency)

	for _, proxy := range proxies {
		wg.Add(1)

		go func(px C.Proxy) {
			defer wg.Done()

			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				return
			}

			probe, cancelProbe := context.WithTimeout(context.Background(), healthCheckProbeTimeout)
			defer cancelProbe()

			checked.Add(1)

			if _, err := px.URLTest(probe, url, expectedStatus); err != nil {
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

		url, expectedStatus := groupCheckOptions(g)
		if url == "" {
			continue
		}

		key := now + "|" + url
		if seen[key] {
			continue
		}

		seen[key] = true

		go func(px C.Proxy, url string, expected utils.IntRanges[uint16]) {
			ctx, cancel := context.WithTimeout(context.Background(), healthCheckProbeTimeout)
			defer cancel()

			delay, err := px.URLTest(ctx, url, expected)
			if err != nil {
				log.Infoln("Probe after network change: %s failed: %s", px.Name(), err.Error())

				return
			}

			log.Infoln("Probe after network change: %s is alive, %d ms", px.Name(), delay)
		}(target, url, expectedStatus)
	}
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}
