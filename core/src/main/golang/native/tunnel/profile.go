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

// TestProfileDelays меряет задержки узлов профиля, НЕ поднимая ядро.
//
// Зачем: до подключения ядро о профиле ничего не знает, `tunnel.Proxies()`
// пуст, и список серверов на экране собирается из `panel.json` — одни имена,
// без задержек и без возможности выбрать узел осмысленно. А человеку как раз
// перед подключением и хочется знать, куда быстрее.
//
// Почему это работает без туннеля: проба до узла — обычный сокет процесса
// до адреса сервера. Ни `tunnel.Tunnel`, ни правила, ни tun в этом пути
// не участвуют; `markSocket` при не поднятом VpnService — пустышка
// (`native/app/tun.go`), а петли нет, потому что нет и туннеля. Ровно так же
// ядро качает саму подписку (`config.FetchAndValid`) — до всякого подключения.
//
// Почему не поднимаем конфигурацию по-настоящему: `hub.ApplyConfig` заменил бы
// глобальное состояние ядра — прокси, правила, DNS, слушатели, — и следующий
// старт службы столкнулся бы с ним. Здесь конфиг только разбирается, узлы
// создаются, опрашиваются и выбрасываются вместе с провайдерами; глобальное
// состояние не трогается вовсе.
//
// Возвращает имя узла -> задержка в мс, `0xffff` для не ответивших, — тот же
// формат, что и у обычного списка, чтобы экран не различал источники.
func TestProfileDelays(path string) map[string]int {
	result := map[string]int{}

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

	// Всё, что создал разбор, надо закрыть руками.
	//
	// Провайдеры держат горутины обновления и файлы — их закрывает
	// `DestroyProviders`, ровно как проверка подписки при импорте. Но узлы
	// закрывать тоже обязательно: у QUIC-протоколов (hysteria2, tuic, anytls,
	// masque) и у мультиплексирующих `Close` не пустой — там живой UDP-сокет
	// и горутины на узел. Без этого каждое нажатие «проверить» оставляло бы
	// по сокету на каждый узел подписки навсегда.
	defer func() {
		for _, p := range cfg.Proxies {
			_ = p.Close()
		}

		config.DestroyProviders(cfg)

		// Разбор подписки — самая тяжёлая по памяти операция в процессе;
		// после неё ядро отдаёт память так же, как после загрузки профиля.
		runtime.GC()
	}()

	url := profileTestURL(rawCfg)

	proxies := make([]C.Proxy, 0, len(cfg.Proxies))

	for _, p := range cfg.Proxies {
		// Группы пропускаем: у них своя логика выбора, и мерить в них нечего —
		// задержка группы это задержка узла, который она выбрала.
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
		// Сюда же попадают подписки на `proxy-providers`: их узлы живут
		// в провайдерах и появляются только после `Initial()`, которого
		// здесь намеренно нет. У Remnawave узлы вставляются инлайном,
		// а список групп до подключения и так строится из `proxies:`
		// (`native/config/panel.go`), так что для наших подписок это
		// ничего не меняет.
		log.Warnln("Test profile `%s`: no inline proxies to test", path)

		return result
	}

	log.Infoln("Test profile `%s`: %d proxies via %s", path, len(proxies), url)

	ctx, cancel := context.WithTimeout(context.Background(), healthCheckTotalTimeout)
	defer cancel()

	var mu sync.Mutex

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

// delayUnknown — то же значение, которым ядро отвечает на «задержки нет»:
// максимум uint16 из `LastDelayForTestUrl`. Экран разбирает его сам.
const delayUnknown = 0xffff

// profileTestURL — адрес проверки для профиля целиком.
//
// Берём первый `url:`, заданный у групп в самом файле подписки: именно по нему
// ядро будет мерить задержки после подключения, и цифры «до» и «после» окажутся
// сравнимыми. Разбирать группы через `MarshalJSON`, как в `groupCheckOptions`,
// здесь незачем: у нас на руках исходный конфиг, а порядок групп в нём
// определён, в отличие от порядка обхода карты прокси.
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
