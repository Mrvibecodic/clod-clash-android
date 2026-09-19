package tunnel

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"cfa/native/common/safego"
	"cfa/native/config"
	"cfa/native/probeoutcome"

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

	healthCheckFreshWindow = 60 * time.Second
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
	probeAbort    context.CancelFunc
	probeInflight = map[string]*probeResult{}

	screenProbes  = &probePool{tag: "screen", slots: make(chan struct{}, healthCheckConcurrency)}
	serviceProbes = &probePool{tag: "service", slots: make(chan struct{}, healthCheckConcurrency)}
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
	need := time.Duration(count/healthCheckConcurrency+2) * healthCheckProbeTimeout

	if need < healthCheckTotalTimeout {
		return healthCheckTotalTimeout
	}

	return need
}

func probeProxy(ctx context.Context, pool *probePool, px C.Proxy, url string, statusKey string, expected utils.IntRanges[uint16]) (uint16, probeoutcome.Outcome, error) {
	key := pool.tag + "|" + px.Name() + "|" + url + "|" + statusKey

	for {
		probeMu.Lock()

		if shared, ok := probeInflight[key]; ok {
			probeMu.Unlock()

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
		case <-ctx.Done():
			own.err = ctx.Err()
			own.outcome = probeoutcome.Classify(own.err, ctx.Err())

			return 0, own.outcome, own.err
		}

		probe, cancel := context.WithTimeout(ctx, healthCheckProbeTimeout)
		defer cancel()

		own.delay, own.err = px.URLTest(probe, url, expected)
		own.outcome = probeoutcome.Classify(own.err, ctx.Err())

		return own.delay, own.outcome, own.err
	}
}

// resolveSelected walks nested groups down to the leaf the group points at:
// probing a group instead of a node touches the group and disables the lazy
// health check of the real node. The lookup goes through the members of the
// group itself, because nodes from proxy providers are not in tunnel.Proxies().
func resolveSelected(g outboundgroup.ProxyGroup) (string, C.Proxy) {
	for depth := 0; depth < 16; depth++ {
		now := g.Now()
		if now == "" {
			return "", nil
		}

		var selected C.Proxy

		for _, px := range g.Proxies() {
			if px.Name() == now {
				selected = px

				break
			}
		}

		if selected == nil {
			return "", nil
		}

		inner, isGroup := selected.Adapter().(outboundgroup.ProxyGroup)
		if !isGroup {
			return now, selected
		}

		g = inner
	}

	return "", nil
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

	url, statusKey, expectedStatus := groupCheckOptions(g)

	targets := make([]checkTarget, 0, len(proxies))

	for _, px := range proxies {
		targets = append(targets, checkTarget{px, url, statusKey, expectedStatus})
	}

	log.Infoln("Health check `%s`: %d proxies via %s", name, len(proxies), url)

	checked, alive := probeTargets(pool, "Health check `"+name+"`", targets)

	log.Infoln(
		"Health check `%s`: %d alive of %d checked, %d of %d not checked",
		name, alive, checked, len(proxies)-checked, len(proxies),
	)

	if checked == 0 {
		return errNothingChecked
	}

	return nil
}

type checkTarget struct {
	proxy    C.Proxy
	url      string
	status   string
	expected utils.IntRanges[uint16]
}

func (t checkTarget) key() string {
	return t.proxy.Name() + "|" + t.url + "|" + t.status
}

var errNothingChecked = errors.New("health check interrupted: no node was checked")

func groupTargets(name string) []checkTarget {
	p := tunnel.Proxies()[name]
	if p == nil {
		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return nil
	}

	url, statusKey, expectedStatus := groupCheckOptions(g)

	targets := []checkTarget{}

	for _, px := range g.Proxies() {
		if _, isGroup := px.Adapter().(outboundgroup.ProxyGroup); isGroup {
			continue
		}

		targets = append(targets, checkTarget{px, url, statusKey, expectedStatus})
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

	for _, name := range names {
		for _, t := range groupTargets(name) {
			key := t.key()

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

	log.Infoln("Health check of %d groups: %d nodes to probe, %d fresh", len(names), len(targets), skipped)

	if len(targets) == 0 {
		return nil
	}

	checked, alive := probeTargets(screenProbes, "Health check", targets)

	log.Infoln(
		"Health check of %d groups: %d alive of %d checked, %d of %d not checked",
		len(names), alive, checked, len(targets)-checked, len(targets),
	)

	if checked == 0 {
		return errNothingChecked
	}

	return nil
}

func probeTargets(pool *probePool, what string, targets []checkTarget) (int, int) {
	ctx, cancel := context.WithTimeout(probeContext(), healthCheckBudget(len(targets)))
	defer cancel()

	var checked, alive atomic.Int32

	wg := &sync.WaitGroup{}

	for _, target := range targets {
		wg.Add(1)

		t := target

		safego.Go("healthCheckProbe", func() {
			defer wg.Done()

			_, outcome, err := probeProxy(ctx, pool, t.proxy, t.url, t.status, t.expected)

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

	return int(checked.Load()), int(alive.Load())
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
		}
	}

	for _, p := range proxies {
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}

		now, target := resolveSelected(g)
		if target == nil {
			continue
		}

		url, statusKey, expectedStatus := groupCheckOptions(g)
		if url == "" {
			continue
		}

		// A group that reselects its node on failure always gets a probe of its
		// own: otherwise the dedup by shared leaf would swallow it.
		reselect := reselectsItself(g)

		key := now + "|" + url
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
		if !ok {
			continue
		}

		url, statusKey, expected := groupCheckOptions(g)
		if url == "" {
			continue
		}

		for _, px := range g.Proxies() {
			if _, isGroup := px.Adapter().(outboundgroup.ProxyGroup); isGroup {
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

			targets = append(targets, checkTarget{px, url, statusKey, expected})
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

	log.Infoln("Recover dead nodes: %d of %d revived", revived.Load(), len(targets))
}
