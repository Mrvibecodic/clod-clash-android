package tunnel

import (
	"context"
	"errors"
	"io"
	"os"
	"runtime"
	"slices"
	"strings"
	"sync"
	"sync/atomic"

	"cfa/native/common/safego"
	"cfa/native/config"
	"cfa/native/config/panel"
	"cfa/native/probeoutcome"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/component/hidden"
	mihomoConfig "github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// profileNodes — узлы подписки без туннеля: настоящие узлы из профиля и из
// уже скачанных провайдеров, каждый по одному разу; url — адрес проверки
// групп; find — узел или группа профиля по имени, как их ищет dialer-proxy.
// release освобождает всё, что разобрано.
func profileNodes(path string) (proxies []C.Proxy, url string, find func(string) C.Proxy, release func(), err error) {
	rawCfg, err := config.UnmarshalAndPatch(path)
	if err != nil {
		return nil, "", nil, func() {}, err
	}

	config.UseProfileDelayMode(rawCfg)

	cfg, err := config.Parse(rawCfg)
	if err != nil {
		return nil, "", nil, func() {}, err
	}

	fromProviders, closeProviders := providerProxies(path, rawCfg)

	release = func() {
		closeProviders()

		for _, p := range cfg.Proxies {
			_ = p.Close()
		}

		config.DestroyProviders(cfg)

		runtime.GC()
	}

	seen := make(map[string]bool, len(cfg.Proxies))

	add := func(p C.Proxy) {
		if _, isGroup := p.Adapter().(outboundgroup.ProxyGroup); isGroup || builtin(p) {
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

	for _, p := range fromProviders {
		add(p)
	}

	find = func(name string) C.Proxy { return cfg.Proxies[name] }

	return proxies, profileTestURL(rawCfg), find, release, nil
}

// TestProfileDelays — задержки узлов подписки без туннеля; заглушки панели
// не проверяются, не в мобильной сети (cellular=false) — и серверы только
// для неё: их не видно — ни их, ни серверов, что соединяются через них, и у
// провайдеров тоже.
func TestProfileDelays(path string, cellular bool) (map[string]int, error) {
	result := map[string]int{}

	// Корень проб берётся ДО разбора профиля: иначе отмена, пришедшая на старте
	// службы, отменяет старый корень, а замер получает свежий и живёт дальше.
	root := probeContext()

	// Новый замер гасит прежний: человек сменил подписку посреди замера, а
	// прежний мерил бы дальше, занимая пробы и подменяя ход замера на экране
	run := startProfileRun(root)
	defer run.finish()

	proxies, url, find, release, err := profileNodes(path)
	defer release()

	if err != nil {
		log.Errorln("Test profile `%s`: %s", path, err.Error())

		return nil, err
	}

	info := panel.Read(path)

	if len(info.Sentinels) > 0 {
		proxies = slices.DeleteFunc(proxies, func(p C.Proxy) bool { return slices.Contains(info.Sentinels, p.Name()) })
	}

	if !cellular && len(info.MobileOnly) > 0 {
		proxies = slices.DeleteFunc(proxies, hidden.HidesBy(info.MobileOnly, find))
	}

	if len(proxies) == 0 {
		log.Warnln("Test profile `%s`: no proxies to test", path)

		return result, nil
	}

	log.Infoln("Test profile `%s`: %d proxies via %s", path, len(proxies), url)

	run.total.Store(int64(len(proxies)))

	ctx, cancel := context.WithTimeout(run.ctx, healthCheckBudget(len(proxies)))
	defer cancel()

	var mu sync.Mutex

	wg := &sync.WaitGroup{}

	for _, proxy := range proxies {
		wg.Add(1)

		px := proxy

		safego.Go("testProfileDelay", func() {
			defer wg.Done()
			defer run.done.Add(1)

			select {
			case screenProbes.slots <- struct{}{}:
				defer func() { <-screenProbes.slots }()
			case <-ctx.Done():
				return
			}

			// Та же проба, что в замере при работающем туннеле. Отмена, истёкший
			// бюджет и проба на смене сети — не приговор узлу: он остаётся
			// непроверенным
			delay, outcome, err := probeNode(ctx, px, url, nil)

			switch outcome {
			case probeoutcome.Superseded, probeoutcome.Expired:
				return
			}

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

	if probeoutcome.Interrupted(len(proxies), len(result), context.Cause(run.ctx), errCoreReset, errConfigReplaced, errRunSuperseded) {
		log.Warnln("Test profile `%s`: interrupted, nothing measured", path)

		return nil, errNothingMeasured
	}

	return result, nil
}

var (
	errNothingMeasured = errors.New("profile delay test interrupted: no node was measured")
	errRunSuperseded   = errors.New("profile delay test superseded")
)

const delayUnknown = 0xffff

// Замер без туннеля: свой выключатель и свой ход — замер, погашенный новым,
// не трогает ход нового
type profileRun struct {
	ctx         context.Context
	cancel      context.CancelCauseFunc
	done, total atomic.Int64
}

var (
	profileRunMu sync.Mutex

	currentProfileRun *profileRun
)

func startProfileRun(root context.Context) *profileRun {
	run := &profileRun{}
	run.ctx, run.cancel = context.WithCancelCause(root)

	profileRunMu.Lock()
	defer profileRunMu.Unlock()

	if prev := currentProfileRun; prev != nil {
		prev.cancel(errRunSuperseded)

		log.Infoln("Test profile: previous test cancelled by a new one")
	}

	currentProfileRun = run

	return run
}

func (run *profileRun) finish() {
	run.cancel(nil)

	profileRunMu.Lock()
	defer profileRunMu.Unlock()

	if currentProfileRun == run {
		currentProfileRun = nil
	}
}

// Ход замера без туннеля для экрана: готово из всего; 0 из 0 — замера нет
func ProfileDelayProgress() (done, total int64) {
	profileRunMu.Lock()
	defer profileRunMu.Unlock()

	if run := currentProfileRun; run != nil {
		return run.done.Load(), run.total.Load()
	}

	return 0, 0
}

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

		// Замер без туннеля только читает скачанный файл: битый не уходит в сеть и не перезаписывается
		mapping["type"] = "file"
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
