package tunnel

import (
	"context"
	"sync"
	"time"

	"cfa/native/common/safego"

	"github.com/metacubex/mihomo/adapter"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// Проверка 16–20 — режется ли трафик через узел: ядро качает кусок через узел
// и отвечает ok / frozen / dead / unknown. Узлы берутся из работающего туннеля
// (path пустой) или из подписки без него, как в замере задержек. Когда и кого
// проверять, решает служба; здесь только вызов ядра.

// downloadChecker — узел ядра с отпечатком и проверкой загрузкой; у групп и
// встроенных политик его нет.
type downloadChecker interface {
	Fingerprint() string
	DownloadCheck(ctx context.Context, url string, size int64, timeout, stall time.Duration, pingURL string) adapter.DownloadResult
}

// DownloadRequest — что проверить и как.
type DownloadRequest struct {
	Path    string   `json:"path"`
	Names   []string `json:"names"`
	URL     string   `json:"url"`
	Size    int64    `json:"size"`
	Timeout int64    `json:"timeout"`
	Stall   int64    `json:"stall"`
}

const (
	// Сколько проверок идёт разом: каждая — 64 КБ через узел.
	downloadConcurrency = 3

	// Одна проверка: очередь к хосту, загрузка, контрольный пинг, запас.
	downloadSpan = C.ProbeReserve + adapter.DownloadPingTimeout + C.ProbeReserve
)

// nodeSource — откуда брать узлы и чем пинговать после пустой загрузки.
type nodeSource struct {
	proxies []C.Proxy
	pingURL func(C.Proxy) string
	release func()
}

func runningNodes() *nodeSource {
	seen := map[string]bool{}
	proxies := []C.Proxy{}

	add := func(p C.Proxy) {
		if seen[p.Name()] {
			return
		}

		seen[p.Name()] = true

		proxies = append(proxies, p)
	}

	eachProxy(add)

	return &nodeSource{proxies: proxies, pingURL: tunnel.DownloadPingURL, release: func() {}}
}

func nodesOf(path string) (*nodeSource, error) {
	if path == "" {
		return runningNodes(), nil
	}

	proxies, url, _, release, err := profileNodes(path)
	if err != nil {
		release()

		return nil, err
	}

	return &nodeSource{proxies: proxies, pingURL: func(C.Proxy) string { return url }, release: release}, nil
}

// NodeFingerprints — имя узла → отпечаток; узлы без отпечатка (чужое ядро,
// группы, политики) не попадают.
func NodeFingerprints(path string) map[string]string {
	result := map[string]string{}

	source, err := nodesOf(path)
	if err != nil {
		log.Errorln("Node fingerprints `%s`: %s", path, err.Error())

		return result
	}

	defer source.release()

	for _, p := range source.proxies {
		if node, ok := p.(downloadChecker); ok && node.Fingerprint() != "" {
			result[p.Name()] = node.Fingerprint()
		}
	}

	return result
}

// DownloadOutcome — итог одной проверки: вердикт и код ответа сайта (0 —
// ответа не было). Код нужен клиенту: «неясно» с кодом значит, что через узел
// что-то прошло, и повторять проверку сразу незачем.
type DownloadOutcome struct {
	Verdict string `json:"verdict"`
	Status  int    `json:"status"`
}

// DownloadChecks — имя узла → итог проверки загрузкой для перечисленных узлов.
// Узел, не найденный у ядра, в ответ не попадает; оборванная проверка (загрузка
// или сброс ядра, смена сети, конец бюджета) — тоже: об узле она ничего не
// говорит. Проверки ждут удержания проб после смены сети и пробуждения.
func DownloadChecks(req DownloadRequest) map[string]DownloadOutcome {
	result := map[string]DownloadOutcome{}

	root := probeContext()

	source, err := nodesOf(req.Path)
	if err != nil {
		log.Errorln("Download checks `%s`: %s", req.Path, err.Error())

		return result
	}

	defer source.release()

	wanted := make(map[string]bool, len(req.Names))
	for _, name := range req.Names {
		wanted[name] = true
	}

	nodes := make([]C.Proxy, 0, len(req.Names))
	for _, p := range source.proxies {
		if _, ok := p.(downloadChecker); ok && wanted[p.Name()] {
			nodes = append(nodes, p)
		}
	}

	if len(nodes) == 0 {
		return result
	}

	timeout := time.Duration(req.Timeout) * time.Millisecond
	stall := time.Duration(req.Stall) * time.Millisecond
	budget := time.Duration(len(nodes)/downloadConcurrency+2) * (downloadSpan + timeout)

	ctx, cancel := context.WithTimeout(root, budget)
	defer cancel()

	slots := make(chan struct{}, downloadConcurrency)

	var mu sync.Mutex

	wg := &sync.WaitGroup{}

	for _, p := range nodes {
		wg.Add(1)

		px := p

		safego.Go("downloadCheck", func() {
			defer wg.Done()

			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			case <-ctx.Done():
				return
			}

			// Провал на смене сети или после сна ядро записало бы как «неясно»
			if waitNetworkSettled(ctx) != nil {
				return
			}

			began := time.Now()

			outcome := px.(downloadChecker).DownloadCheck(ctx, req.URL, req.Size, timeout, stall, source.pingURL(px))

			// Проверку оборвали загрузка или сброс ядра, смена сети или конец
			// бюджета захода — об узле она ничего не говорит: в итог не идёт,
			// иначе «неясно» записалось бы как попытка и узел ждал бы перепроверки
			if ctx.Err() != nil {
				return
			}

			// «Неясно», когда посреди проверки началось удержание (сон, смена
			// сети), — тоже оборванная проверка
			if outcome.Verdict == adapter.DownloadUnknown && (C.ProbeHolding(began) || C.ProbeHolding(time.Now())) {
				return
			}

			mu.Lock()
			defer mu.Unlock()

			result[px.Name()] = DownloadOutcome{Verdict: outcome.Verdict, Status: outcome.Status}
		})
	}

	wg.Wait()

	log.Infoln("Download checks `%s`: %d of %d answered", req.Path, len(result), len(nodes))

	return result
}
