package tunnel

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"cfa/native/common/safego"
	"cfa/native/config"
	"cfa/native/probeoutcome"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

const (
	healthCheckConcurrency = 10

	healthCheckProbeTimeout = 5 * time.Second

	healthCheckTotalTimeout = 45 * time.Second

	healthCheckFreshWindow = 60 * time.Second

	// probeSpan — сколько может идти проба узла: ядро ставит повторную пробу
	// рядом с зависшей первой, и у второй свой тайм-аут
	probeSpan = provider.ProbeHedgeDelay + healthCheckProbeTimeout
)

type probeResult struct {
	done    chan struct{}
	outcome probeoutcome.Outcome
	delay   uint16
	err     error
}

type probePool struct {
	tag   string
	slots chan struct{}
}

var (
	probeMu       sync.Mutex
	probeRoot     context.Context
	probeAbort    context.CancelCauseFunc
	probeInflight = map[string]*probeResult{}

	screenProbes  = &probePool{tag: "screen", slots: make(chan struct{}, healthCheckConcurrency)}
	serviceProbes = &probePool{tag: "service", slots: make(chan struct{}, healthCheckConcurrency)}
)

func probeContext() context.Context {
	probeMu.Lock()
	defer probeMu.Unlock()

	if probeRoot == nil || probeRoot.Err() != nil {
		probeRoot, probeAbort = context.WithCancelCause(context.Background())
	}

	return probeRoot
}

var errConfigReplaced = errors.New("config replaced")

func CancelHealthChecks() {
	cancelHealthChecks(nil)
}

// Замер, начатый до загрузки конфига, меряет выброшенные объекты прежнего
func CancelHealthChecksOfOldConfig() {
	cancelHealthChecks(errConfigReplaced)
}

func cancelHealthChecks(cause error) {
	probeMu.Lock()
	defer probeMu.Unlock()

	if probeAbort != nil {
		probeAbort(cause)
	}
}

func CloseProviders() {
	for _, p := range tunnel.Providers() {
		if closer, ok := p.(io.Closer); ok {
			_ = closer.Close()
		}
	}

	proxies := tunnel.Proxies()

	safego.Go("closeProviders", func() {
		for _, p := range proxies {
			_ = p.Close()
		}
	})
}

func healthCheckBudget(count int) time.Duration {
	need := time.Duration(count/healthCheckConcurrency+2) * probeSpan

	if need < healthCheckTotalTimeout {
		return healthCheckTotalTimeout
	}

	return need
}

func probeProxy(ctx context.Context, pool *probePool, px C.Proxy, url string, statusKey string, expected utils.IntRanges[uint16]) (uint16, probeoutcome.Outcome, error) {
	// Узел — объект, как в ядре: тёзка от другого провайдера — другой сервер
	key := fmt.Sprintf("%s|%p|%s|%s", pool.tag, px, url, statusKey)

	for {
		probeMu.Lock()

		if shared, ok := probeInflight[key]; ok {
			probeMu.Unlock()

			probeAdmitted(ctx)

			select {
			case <-shared.done:
				stale := shared.outcome == probeoutcome.Superseded || shared.outcome == probeoutcome.Expired

				if stale && ctx.Err() == nil {
					continue
				}

				return shared.delay, shared.outcome, shared.err
			case <-ctx.Done():
				return 0, probeoutcome.Classify(ctx.Err(), ctx.Err()), ctx.Err()
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

		if err := waitNetworkSettled(ctx); err != nil {
			own.err = err
			own.outcome = probeoutcome.Classify(own.err, ctx.Err())

			return 0, own.outcome, own.err
		}

		select {
		case pool.slots <- struct{}{}:
			defer func() { <-pool.slots }()

			probeAdmitted(ctx)
		case <-ctx.Done():
			own.err = ctx.Err()
			own.outcome = probeoutcome.Classify(own.err, ctx.Err())

			return 0, own.outcome, own.err
		}

		own.delay, own.outcome, own.err = probeNode(ctx, px, url, expected)

		return own.delay, own.outcome, own.err
	}
}

// probeNode — проба узла, когда слот пробы уже взят: очередь к хосту, проба
// ядра, исход. Expired и Superseded об узле ничего не говорят
func probeNode(ctx context.Context, px C.Proxy, url string, expected utils.IntRanges[uint16]) (uint16, probeoutcome.Outcome, error) {
	// Пробу, которую обрежет бюджет круга, ядро записало бы узлу как провал;
	// проверка и до очереди, чтобы такая проба её не бронировала
	if deadline, ok := ctx.Deadline(); ok && time.Until(deadline) < probeSpan+probePaceMargin {
		return 0, probeoutcome.Expired, context.DeadlineExceeded
	}

	// Очередь проб к одному хосту ждём до отсчёта тайм-аута пробы: внутри
	// URLTest ожидание съело бы до двух секунд из её пяти
	if err := paceProbe(ctx, px); err != nil {
		return 0, probeoutcome.Classify(err, ctx.Err()), err
	}

	// Пробу, которую обрежет бюджет круга, ядро записало бы узлу как провал
	if deadline, ok := ctx.Deadline(); ok && time.Until(deadline) < probeSpan {
		return 0, probeoutcome.Expired, context.DeadlineExceeded
	}

	// Проба та же, что у плановой проверки ядра: провал подтверждается
	// второй пробой, а идущая проба ядра того же узла отдаёт свой результат
	delay, err := provider.ProbeNode(C.MarkProbePaced(ctx), px, url, expected, healthCheckProbeTimeout)

	// Проба на смене сети или после заморозки процесса об узле ничего не говорит
	if errors.Is(err, provider.ErrProbeDiscarded) {
		return 0, probeoutcome.Superseded, err
	}

	return delay, probeoutcome.Classify(err, ctx.Err()), err
}

// probePaceMargin — запас сверх тайм-аута пробы: ожидание, упёршееся в свой
// предел, кончается чуть позже расчётного, и без запаса проба после него не
// проходила бы проверку «на пробу осталось пять секунд».
const probePaceMargin = 100 * time.Millisecond

type probeAdmittedKey struct{}

// withProbeAdmitted вешает на контекст пробы сигнал «проба получила слот»:
// по нему остальные пробы круга перестают ждать своей очереди за ней
func withProbeAdmitted(ctx context.Context, admitted func()) context.Context {
	return context.WithValue(ctx, probeAdmittedKey{}, admitted)
}

func probeAdmitted(ctx context.Context) {
	if admitted, ok := ctx.Value(probeAdmittedKey{}).(func()); ok {
		admitted()
	}
}

// paceProbe ждёт очередь проб к хосту узла так, чтобы до конца круга на саму
// пробу осталось probeSpan: ядро оставляет только C.ProbeReserve,
// и проба, дождавшаяся очереди, иначе не укладывалась бы в круг, а её бронь
// отодвигала бы следующие.
func paceProbe(ctx context.Context, px C.Proxy) error {
	if deadline, ok := ctx.Deadline(); ok {
		var cancel context.CancelFunc
		ctx, cancel = context.WithDeadline(ctx, deadline.Add(C.ProbeReserve-probeSpan-probePaceMargin))
		defer cancel()
	}

	return C.ProbePace(ctx, C.ProbeHostOf(px))
}

// currentNode — узел, через который группа ведёт трафик сейчас: вложенные
// группы проходятся до узла, на каждом уровне без нового выбора. Проба группы
// вместо узла трогает группу и выключает ленивую проверку самого узла; узел
// берётся из членов группы — узлов провайдеров в tunnel.Proxies() нет. nil —
// группа ещё ни на что не указывает (url-test до первого соединения)
func currentNode(g outboundgroup.ProxyGroup) C.Proxy {
	for depth := 0; depth < 16; depth++ {
		p := currentOf(g)
		if p == nil {
			return nil
		}

		inner, isGroup := p.Adapter().(outboundgroup.ProxyGroup)
		if !isGroup {
			return p
		}

		g = inner
	}

	return nil
}

func groupCheckOptions(g outboundgroup.ProxyGroup) (string, string, utils.IntRanges[uint16]) {
	url := ""
	status := ""

	// Не через JSON группы: он спрашивает текущий узел, а url-test на этом
	// делает новый выбор и держит его 10 с — дольше, чем идёт проверка
	if o, ok := g.(outboundgroup.CheckOptions); ok {
		u, st := o.TestOptions()
		url, status = strings.TrimSpace(u), strings.TrimSpace(st)
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

func HealthCheck(name string) error {
	return healthCheckGroup(screenProbes, name)
}

func healthCheckGroup(pool *probePool, name string) error {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return nil
	}

	proxies := g.Proxies()
	if len(proxies) == 0 {
		log.Warnln("Request health check for `%s`: group is empty", name)

		return nil
	}

	// Все серверы группы скрыты (только для мобильной сети): проверять нечего,
	// и это не «живых нет»
	if outboundgroup.AllHidden(proxies) {
		log.Infoln("Health check `%s`: every server is hidden", name)

		return nil
	}

	url, statusKey, expectedStatus := groupCheckOptions(g)
	now := currentMember(g)

	targets := make([]checkTarget, 0, len(proxies))

	for _, px := range proxies {
		targets = append(targets, checkTarget{proxy: px, url: url, status: statusKey, expected: expectedStatus, first: px.Name() == now})
	}

	log.Infoln("Health check `%s`: %d proxies via %s", name, len(proxies), url)

	checked, alive, replaced := probeTargets(pool, "Health check `"+name+"`", targets)

	log.Infoln(
		"Health check `%s`: %d alive of %d checked, %d of %d not checked",
		name, alive, checked, len(proxies)-checked, len(proxies),
	)

	if checked == 0 && !replaced {
		return errNothingChecked
	}

	return nil
}

type checkTarget struct {
	proxy    C.Proxy
	url      string
	status   string
	expected utils.IntRanges[uint16]
	// first — то, на что группа указывает сейчас (узел или вложенная группа
	// на пути к нему): идёт в очередь проб раньше остальных
	first bool
}

func (t checkTarget) key() string {
	return fmt.Sprintf("%p|%s|%s", t.proxy, t.url, t.status)
}

var errNothingChecked = errors.New("health check interrupted: no node was checked")

// Встроенные исходящие (DIRECT, REJECT и др.) — не узлы: пробовать их нечего или незачем
func builtin(p C.Proxy) bool {
	switch p.Type() {
	case C.Direct, C.Reject, C.RejectDrop, C.Pass, C.PassRule, C.Compatible, C.Dns:
		return true
	default:
		return false
	}
}

// GLOBAL ведёт трафик только в глобальном режиме
func routesTraffic(g outboundgroup.ProxyGroup) bool {
	return g.Name() != "GLOBAL" || tunnel.Mode() == tunnel.Global
}

// currentOf — то, на что группа указывает сейчас (узел или вложенная группа).
// У url-test и fallback без нового выбора: выбор url-test кэшируется на 10 с
// и, сделанный перед проверкой, пережил бы её результаты, а fallback снял бы
// закрепление с узла, мёртвого в эту секунду. Узел ищется среди членов, как
// в ядре: тот же объект, иначе тот же по имени от того же провайдера —
// url-test держит прежний объект после обновления провайдера до первого
// соединения, а проба и история нужны у нынешнего. Тёзка от другого
// провайдера — другой сервер
func currentOf(g outboundgroup.ProxyGroup) C.Proxy {
	members := g.Proxies()

	c, ok := g.(outboundgroup.Pinnable)
	if !ok {
		now := g.Now()

		for _, px := range members {
			if px.Name() == now {
				return px
			}
		}

		return nil
	}

	current := c.CurrentNode()
	if current == nil {
		return nil
	}

	for _, px := range members {
		if px == current {
			return px
		}
	}

	provider := current.ProxyInfo().ProviderName

	for _, px := range members {
		if px.Name() == current.Name() && px.ProxyInfo().ProviderName == provider {
			return px
		}
	}

	return nil
}

// currentMember — имя того, на что группа указывает сейчас
func currentMember(g outboundgroup.ProxyGroup) string {
	if p := currentOf(g); p != nil {
		return p.Name()
	}

	return ""
}

func groupTargets(name string) []checkTarget {
	p := tunnel.Proxies()[name]
	if p == nil {
		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return nil
	}

	members := g.Proxies()
	if outboundgroup.AllHidden(members) {
		return nil
	}

	url, statusKey, expectedStatus := groupCheckOptions(g)
	now := currentMember(g)

	targets := []checkTarget{}

	for _, px := range members {
		if _, isGroup := px.Adapter().(outboundgroup.ProxyGroup); isGroup {
			continue
		}

		targets = append(targets, checkTarget{proxy: px, url: url, status: statusKey, expected: expectedStatus, first: px.Name() == now})
	}

	return targets
}

func probedRecently(px C.Proxy, url string, now time.Time) bool {
	history := px.ExtraDelayHistories()[url].History
	if len(history) == 0 {
		return false
	}

	last := history[len(history)-1]

	return last.Delay > 0 && !last.Time.After(now) && now.Sub(last.Time) < healthCheckFreshWindow
}

func HealthCheckGroups(names []string, exclude []string, force bool) error {
	seen := map[string]bool{}

	for _, name := range exclude {
		for _, t := range groupTargets(name) {
			seen[t.key()] = true
		}
	}

	targets := []checkTarget{}
	skipped := 0
	now := time.Now()
	// Один узел в нескольких группах остаётся одной пробой; первым он идёт,
	// если хоть одна из групп указывает на него
	first := map[string]bool{}

	for _, name := range names {
		for _, t := range groupTargets(name) {
			key := t.key()

			if t.first {
				first[key] = true
			}

			if seen[key] {
				continue
			}

			seen[key] = true

			if !force && probedRecently(t.proxy, t.url, now) {
				skipped++

				continue
			}

			targets = append(targets, t)
		}
	}

	for i := range targets {
		targets[i].first = first[targets[i].key()]
	}

	log.Infoln("Health check of %d groups: %d nodes to probe, %d fresh", len(names), len(targets), skipped)

	if len(targets) == 0 {
		return nil
	}

	checked, alive, replaced := probeTargets(screenProbes, "Health check", targets)

	log.Infoln(
		"Health check of %d groups: %d alive of %d checked, %d of %d not checked",
		len(names), alive, checked, len(targets)-checked, len(targets),
	)

	if checked == 0 && !replaced {
		return errNothingChecked
	}

	return nil
}

// replaced — круг прервала загрузка нового конфига: это не отказ проверки,
// новый конфиг меряет круг, который ставит его загрузка
func probeTargets(pool *probePool, what string, targets []checkTarget) (int, int, bool) {
	ctx, cancel := context.WithTimeout(probeContext(), healthCheckBudget(len(targets)))
	defer cancel()

	var checked, alive atomic.Int32

	// Узлы, которыми группы пользуются сейчас, получают слоты первыми: с десятью
	// пробами за раз такой узел в длинном списке иначе ждал бы до конца круга.
	// Остальные ждут, пока каждый из них возьмёт слот или отпадёт
	head := &sync.WaitGroup{}

	for _, target := range targets {
		if target.first {
			head.Add(1)
		}
	}

	wg := &sync.WaitGroup{}

	for _, target := range targets {
		wg.Add(1)

		t := target

		safego.Go("healthCheckProbe", func() {
			defer wg.Done()

			probeCtx := ctx

			if t.first {
				var once sync.Once

				admitted := func() { once.Do(head.Done) }

				defer admitted()

				probeCtx = withProbeAdmitted(ctx, admitted)
			} else {
				head.Wait()
			}

			_, outcome, err := probeProxy(probeCtx, pool, t.proxy, t.url, t.status, t.expected)

			if outcome == probeoutcome.Superseded || outcome == probeoutcome.Expired {
				return
			}

			checked.Add(1)

			if err != nil {
				log.Debugln("%s: %s failed: %s", what, t.proxy.Name(), err.Error())

				return
			}

			alive.Add(1)
		})
	}

	wg.Wait()

	resetChoices()

	return int(checked.Load()), int(alive.Load()), errors.Is(context.Cause(ctx), errConfigReplaced)
}

// resetChoices — после проб клиента url-test выбирает заново по их
// результатам, а не держит выбор, сделанный до них (он кэшируется на 10 с)
func resetChoices() {
	for _, p := range tunnel.Proxies() {
		if u, ok := p.Adapter().(*outboundgroup.URLTest); ok {
			u.ResetChoice()
		}
	}
}

func ProbeCurrentNodes() {
	if !config.IsLoaded() {
		return
	}

	proxies := tunnel.Proxies()
	seen := make(map[string]bool, len(proxies))

	ctx, cancel := context.WithTimeout(probeContext(), healthCheckTotalTimeout)

	var pending atomic.Int32

	pending.Add(1)

	release := func() {
		if pending.Add(-1) == 0 {
			cancel()
			resetChoices()
		}
	}

	for _, p := range proxies {
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok || !routesTraffic(g) {
			continue
		}

		target := currentNode(g)
		if target == nil || builtin(target) {
			continue
		}

		url, statusKey, expectedStatus := groupCheckOptions(g)
		if url == "" {
			continue
		}

		// A group that reselects its node on failure always gets a probe of its
		// own: otherwise the dedup by shared leaf would swallow it.
		reselect := reselectsItself(g)

		key := target.Name() + "|" + target.ProxyInfo().ProviderName + "|" + url
		if reselect {
			key = g.Name() + "|" + key
		}

		if seen[key] {
			continue
		}

		seen[key] = true

		pending.Add(1)

		px, group := target, g.Name()

		safego.Go("probeCurrentNode", func() {
			defer release()

			delay, outcome, err := probeProxy(ctx, serviceProbes, px, url, statusKey, expectedStatus)

			switch outcome {
			case probeoutcome.Alive:
				log.Infoln("Probe after network change: %s is alive, %d ms", px.Name(), delay)
			case probeoutcome.Superseded:
			default:
				if err != nil {
					log.Infoln("Probe after network change: %s failed: %s", px.Name(), err.Error())
				} else {
					log.Infoln("Probe after network change: %s did not finish in the round budget", px.Name())
				}

				if reselect {
					safego.Go("healthCheck", func() {
						_ = healthCheckGroup(serviceProbes, group)
					})
				}
			}
		})
	}

	release()
}

func reselectsItself(g outboundgroup.ProxyGroup) bool {
	switch g.Type() {
	case C.URLTest, C.Fallback:
		return true
	default:
		return false
	}
}

const recoverCooldown = 20 * time.Second

var (
	recoverBusy   atomic.Bool
	recoverLastAt atomic.Int64
)

func RecoverDeadNodes(force bool) {
	if !config.IsLoaded() {
		return
	}

	if !recoverBusy.CompareAndSwap(false, true) {
		return
	}

	defer recoverBusy.Store(false)

	if !force && time.Since(time.Unix(0, recoverLastAt.Load())) < recoverCooldown {
		return
	}

	targets := []checkTarget{}
	seen := map[string]bool{}

	for _, p := range tunnel.Proxies() {
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok || !routesTraffic(g) {
			continue
		}

		url, statusKey, expected := groupCheckOptions(g)
		if url == "" {
			continue
		}

		for _, px := range g.Proxies() {
			if _, isGroup := px.Adapter().(outboundgroup.ProxyGroup); isGroup || builtin(px) {
				continue
			}

			if px.AliveForTestUrl(url) {
				continue
			}

			key := px.Name() + "|" + url
			if seen[key] {
				continue
			}

			seen[key] = true

			targets = append(targets, checkTarget{proxy: px, url: url, status: statusKey, expected: expected})
		}
	}

	if len(targets) == 0 {
		return
	}

	recoverLastAt.Store(time.Now().UnixNano())

	log.Infoln("Recover dead nodes: %d to re-probe", len(targets))

	ctx, cancel := context.WithTimeout(probeContext(), healthCheckBudget(len(targets)))
	defer cancel()

	wg := &sync.WaitGroup{}

	var revived atomic.Int32

	for _, t := range targets {
		wg.Add(1)

		t := t

		safego.Go("recoverDeadNode", func() {
			defer wg.Done()

			delay, outcome, _ := probeProxy(ctx, serviceProbes, t.proxy, t.url, t.status, t.expected)

			if outcome == probeoutcome.Alive {
				revived.Add(1)

				log.Infoln("Recover dead nodes: %s alive, %d ms", t.proxy.Name(), delay)
			}
		})
	}

	wg.Wait()

	resetChoices()

	log.Infoln("Recover dead nodes: %d of %d revived", revived.Load(), len(targets))
}
