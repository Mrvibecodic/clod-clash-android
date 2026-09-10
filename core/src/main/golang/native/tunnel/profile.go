package tunnel

import (
	"context"
	"io"
	"os"
	"runtime"
	"strings"
	"sync"

	"cfa/native/common/safego"
	"cfa/native/config"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/adapter/provider"
	mihomoConfig "github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
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
	seen := make(map[string]bool, len(cfg.Proxies))

	add := func(p C.Proxy) {
		if _, isGroup := p.Adapter().(outboundgroup.ProxyGroup); isGroup {
			return
		}

		switch p.Type() {
		case C.Direct, C.Reject, C.RejectDrop, C.Pass, C.PassRule, C.Compatible, C.Dns:
			return
		}

		if seen[p.Name()] {
			return
		}

		seen[p.Name()] = true

		proxies = append(proxies, p)
	}

	for _, p := range cfg.Proxies {
		add(p)
	}

	fromProviders, closeProviders := providerProxies(path, rawCfg)
	defer closeProviders()

	for _, p := range fromProviders {
		add(p)
	}

	if len(proxies) == 0 {
		log.Warnln("Test profile `%s`: no proxies to test", path)

		return result
	}

	log.Infoln("Test profile `%s`: %d proxies via %s", path, len(proxies), url)

	ctx, cancel := context.WithTimeout(root, healthCheckBudget(len(proxies)))
	defer cancel()

	var mu sync.Mutex

	wg := &sync.WaitGroup{}

	for _, proxy := range proxies {
		wg.Add(1)

		px := proxy

		safego.Go("testProfileDelay", func() {
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
		})
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

func providerProxies(path string, rawCfg *mihomoConfig.RawConfig) ([]C.Proxy, func()) {
	proxies := []C.Proxy{}
	created := []P.ProxyProvider{}

	closeAll := func() {
		for _, pd := range created {
			if closer, ok := pd.(io.Closer); ok {
				_ = closer.Close()
			}
		}
	}

	for name, raw := range rawCfg.ProxyProvider {
		file, _ := raw["path"].(string)
		if file == "" {
			continue
		}

		if _, err := os.Stat(file); err != nil {
			log.Infoln("Test profile `%s`: provider %s not downloaded yet, skipped", path, name)

			continue
		}

		mapping := make(map[string]any, len(raw)+2)
		for key, value := range raw {
			mapping[key] = value
		}

		mapping["interval"] = 0
		mapping["health-check"] = map[string]any{"enable": false}

		pd, err := provider.ParseProxyProvider(name, mapping, tunnel.Tunnel)
		if err != nil {
			log.Warnln("Test profile `%s`: provider %s: %s", path, name, err.Error())

			continue
		}

		created = append(created, pd)

		if err := pd.Initial(); err != nil {
			log.Warnln("Test profile `%s`: provider %s: %s", path, name, err.Error())

			continue
		}

		proxies = append(proxies, pd.Proxies()...)
	}

	return proxies, closeAll
}
