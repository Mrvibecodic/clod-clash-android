package tunnel

import (
	"context"
	"runtime"
	"strings"
	"sync"

	"cfa/native/config"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	mihomoConfig "github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

func TestProfileDelays(path string) map[string]int {
	result := map[string]int{}

	// Корень проб берётся ДО разбора профиля: иначе отмена, пришедшая на старте
	// службы, отменяет старый корень, а замер получает свежий и живёт дальше.
	root := probeContext()

	rawCfg, err := config.UnmarshalAndPatch(path)
	if err != nil {
		log.Errorln("Test profile `%s`: %s", path, err.Error())

		return result
	}

	cfg, err := config.Parse(rawCfg)
	if err != nil {
		log.Errorln("Test profile `%s`: %s", path, err.Error())

		return result
	}

	defer func() {
		for _, p := range cfg.Proxies {
			_ = p.Close()
		}

		config.DestroyProviders(cfg)

		runtime.GC()
	}()

	url := profileTestURL(rawCfg)

	proxies := make([]C.Proxy, 0, len(cfg.Proxies))

	for _, p := range cfg.Proxies {
		if _, isGroup := p.Adapter().(outboundgroup.ProxyGroup); isGroup {
			continue
		}

		switch p.Type() {
		case C.Direct, C.Reject, C.RejectDrop, C.Pass, C.PassRule, C.Compatible, C.Dns:
			continue
		}

		proxies = append(proxies, p)
	}

	if len(proxies) == 0 {
		log.Warnln("Test profile `%s`: no inline proxies to test", path)

		return result
	}

	log.Infoln("Test profile `%s`: %d proxies via %s", path, len(proxies), url)

	ctx, cancel := context.WithTimeout(root, healthCheckBudget(len(proxies)))
	defer cancel()

	var mu sync.Mutex

	wg := &sync.WaitGroup{}

	for _, proxy := range proxies {
		wg.Add(1)

		go func(px C.Proxy) {
			defer wg.Done()

			select {
			case probeSlots <- struct{}{}:
				defer func() { <-probeSlots }()
			case <-ctx.Done():
				return
			}

			probe, cancelProbe := context.WithTimeout(ctx, healthCheckProbeTimeout)
			defer cancelProbe()

			delay, err := px.URLTest(probe, url, nil)

			mu.Lock()
			defer mu.Unlock()

			if err != nil {
				log.Debugln("Test profile: %s failed: %s", px.Name(), err.Error())

				result[px.Name()] = delayUnknown

				return
			}

			result[px.Name()] = int(delay)
		}(proxy)
	}

	wg.Wait()

	alive := 0

	for _, d := range result {
		if d != delayUnknown {
			alive++
		}
	}

	log.Infoln(
		"Test profile `%s`: %d alive of %d checked, %d of %d not checked",
		path, alive, len(result), len(proxies)-len(result), len(proxies),
	)

	return result
}

const delayUnknown = 0xffff

func profileTestURL(rawCfg *mihomoConfig.RawConfig) string {
	for _, group := range rawCfg.ProxyGroup {
		if u, ok := group["url"].(string); ok {
			if u = strings.TrimSpace(u); u != "" {
				return u
			}
		}
	}

	return C.DefaultTestURL
}
