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
	// Сколько узлов проверяем одновременно. У панели их бывает под сотню,
	// а проверка — это полноценное соединение через узел: без ограничения
	// телефон на секунду уходит в полку. Столько же берёт сам mihomo
	// в `HealthCheck.check` (`adapter/provider/healthcheck.go`).
	healthCheckConcurrency = 10

	// Таймаут ОДНОЙ пробы. Ровно как у mihomo: там на каждый узел свой
	// контекст с дефолтными 5 с. Общего дедлайна на группу у ядра нет,
	// и делать его нельзя: узел, чья проба не успела до общего срока,
	// получил бы `deadline exceeded` и был бы записан в мёртвые, хотя
	// он просто медленный.
	healthCheckProbeTimeout = 5 * time.Second

	// Верхняя граница на всю проверку группы. Пробы, которые уже пошли,
	// она не обрывает — только перестаёт запускать новые. У непроверенных
	// узлов остаётся прежняя задержка, и в лог пишется, скольких успели.
	//
	// Срок с запасом: при параллелизме выше он ограничивает только совсем
	// патологический случай — сотня узлов, каждый из которых молчит все
	// пять секунд. Живые узлы отвечают за десятые доли секунды, и до этой
	// границы дело не доходит.
	healthCheckTotalTimeout = 45 * time.Second
)

// groupCheckOptions — адрес и ожидаемый код ответа, с которыми надо проверять
// группу, то есть ровно те, с которыми её проверяет само ядро.
//
// Читаем их из JSON-представления группы: полей `testUrl`/`expectedStatus`
// в интерфейсе `outboundgroup.ProxyGroup` нет, а в `MarshalJSON` они есть
// у всех типов (`adapter/outboundgroup/urltest.go`, `selector.go` и соседние).
// Промахнуться адресом нельзя: и `url-test` выбирает быстрейший узел по
// `LastDelayForTestUrl(его url)`, и список в интерфейсе показывает задержку
// по конкретному адресу — проверка отработает, а на экране ничего не изменится.
//
// Запасной вариант — адрес проверки провайдера: у групп с инлайновым списком
// `proxies:` mihomo заводит «совместимый» провайдер с адресом группы, а если
// у группы его нет, подставляет `C.DefaultTestURL`
// (`adapter/outboundgroup/parser.go`).
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

	// Пустая строка и «*» означают «подходит любой ответ»; пустой
	// `IntRanges` в mihomo означает ровно это же (`IntRanges.Check`).
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

// GroupTestURL — адрес, по которому ядро проверяет узлы группы. По нему же
// читается задержка для списка серверов.
func GroupTestURL(g outboundgroup.ProxyGroup) string {
	url, _ := groupCheckOptions(g)

	return url
}

// HealthCheck меряет задержки всех узлов группы.
//
// Раньше здесь звался `provider.HealthCheck()` у каждого провайдера группы,
// и это молчаливо ничего не делало, когда у провайдера пустой адрес проверки:
// `HealthCheck.execute` в mihomo при пустом URL просто выходит («skipped due
// to testUrl is empty»). Плюс путь через провайдер завязан на его настройки
// (`interval`, `lazy`, склейка вызовов в пределах секунды) — для проверки
// по кнопке это лишнее: человек нажал, значит мерить надо сейчас.
//
// Поэтому гоняем пробы сами, с явным адресом и явным таймаутом.
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

// ProbeCurrentNodes — проба ТЕКУЩЕГО узла каждой группы, по одной на группу.
//
// Зовётся после смены сети. Полная проверка группы (`HealthCheck`) здесь была
// бы неоправданной: узлов в подписке бывают десятки, а вопрос всего один —
// жив ли тот узел, через который человек сейчас работает. Если он не ответил,
// группы с автоматическим выбором уведут сами: у них внутри та же проба,
// и результат ложится в ту же историю задержек.
//
// Узел, общий для нескольких групп, проверяется один раз на адрес проверки:
// объект узла в ядре один на имя, а вот адрес проверки у каждой группы свой,
// и задержка хранится по адресу.
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
	// Здесь именно все группы, включая невыбираемые: узлы у групп общие
	// (`tunnel.Proxies()` держит по одному объекту на имя), но адрес проверки
	// у каждой группы свой, и задержка читается по адресу своей группы.
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}
