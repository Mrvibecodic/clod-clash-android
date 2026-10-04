package report

import (
	"sync"
	"time"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// Сборщик: читает то, что ядро уже намерило, и раскладывает по месту — сети
// и внешнему адресу клиента в ней. Своих проб нет.
//
// Все замеры: ядро держит у узла только 10 последних, поэтому сборщик читает
// их тем чаще, чем чаще узлы проверяются (от 20 секунд до 5 минут), окно
// считается в миллисекундах и сдвигается, только когда замеры прочитаны.
//
// Замер лежит в той сети и при том адресе, где сделан. Сеть называет служба
// (NetworkChanged) в момент смены: намеренное до смены уходит в старое место,
// последние секунды перед ней отбрасываются, новое место узнаётся сразу, с
// новым адресом. В одной сети адрес переспрашивается раз в час.

const (
	tickMax = 5 * time.Minute
	tickMin = 20 * time.Second
	// Чтение соединений: байты по узлам. Ядро отдаёт только живые соединения,
	// закрытое между чтениями теряется целиком — поэтому часто: теряется лишь
	// хвост последних секунд каждого соединения.
	trafficTick = 10 * time.Second
	// Столько последних замеров ядро держит у узла (defaultHistoriesNum).
	historyCap = 10
	// Замеры за столько до смены сети не привязать ни к старой сети, ни к новой.
	changeGuardMs = 5_000
	// Внешний адрес в одной сети переспрашивается не чаще этого.
	ipEvery = 60 * 60
	// Не узнался — переспрашивается не чаще этого.
	ipRetry = 5 * 60
)

type spot struct {
	place   Place
	changes uint64
	ipAt    int64
}

func (s *spot) ipIsDue(now int64) bool {
	unknown := s.place.IP4 == "" && s.place.IP6 == ""
	if unknown {
		return now-s.ipAt >= ipRetry
	}

	return now-s.ipAt >= ipEvery
}

// Байты через узел с прошлого сброса в файл: выгрузка, загрузка, секунды с трафиком.
type traffic map[string][3]uint64

var (
	// Чтение замеров и смена места идут по одному.
	collectMu sync.Mutex
	// Файлы накопленного читает и пишет кто-то один.
	filesMu sync.Mutex

	mu            sync.Mutex
	target        string
	nodes         = map[string]NodeInfo{}
	nodeProviders []ProviderSource
	netNow        string
	kindNow       string
	changes       uint64
	where         *spot
	pingsUntil    int64
	seen          = map[string][2]int64{}
	readAt        int64
	gathered      = traffic{}
	next          = tickMin
	running       bool
	wake          = make(chan struct{}, 1)
)

func nowMs() int64 { return time.Now().UnixMilli() }

// SetTarget — файл накопленного подписки, по которой идёт сбор; пусто — сбор
// выключен (подписка без защищённого канала, туннель остановлен). Замеры
// считаются с этого момента.
func SetTarget(path string) {
	mu.Lock()

	if path != target {
		target = path
		where = nil
		pingsUntil = nowMs()
		seen = map[string][2]int64{}
		readAt = 0
		gathered = traffic{}
		next = tickMin
	}

	start := path != "" && !running
	if start {
		running = true
	}

	mu.Unlock()

	if start {
		go loop()
		go trafficLoop()
	}
}

func loop() {
	for {
		mu.Lock()
		wait := next
		on := target != ""
		if !on {
			running = false
		}
		mu.Unlock()

		if !on {
			return
		}

		select {
		case <-time.After(wait):
		case <-wake:
		}

		tick()
	}
}

func trafficLoop() {
	for {
		time.Sleep(trafficTick)

		mu.Lock()
		on := target != "" && running
		mu.Unlock()

		if !on {
			return
		}

		trafficTickOnce()
	}
}

// trafficTickOnce — прирост байтов соединений с прошлого чтения в копилку по
// узлам. Первое чтение только запоминает счётчики: прошлое соединений не в счёт.
func trafficTickOnce() {
	mu.Lock()
	was := seen
	primed := readAt > 0
	mu.Unlock()

	alive := map[string][2]int64{}
	added := map[string][2]uint64{}

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		info := c.Info()
		if len(info.Chain) == 0 {
			return true
		}

		id := info.UUID.String()
		up, down := info.UploadTotal.Load(), info.DownloadTotal.Load()
		prev := was[id]

		dUp, dDown := max(up-prev[0], 0), max(down-prev[1], 0)
		if dUp > 0 || dDown > 0 {
			sum := added[info.Chain[0]]
			sum[0] += uint64(dUp)
			sum[1] += uint64(dDown)
			added[info.Chain[0]] = sum
		}

		alive[id] = [2]int64{up, down}

		return true
	})

	now := nowMs()

	mu.Lock()
	defer mu.Unlock()

	secs := uint64(1)
	if readAt > 0 {
		secs = uint64(min(max((now-readAt)/1000, 1), int64(trafficTick/time.Second)*3))
	}
	readAt = now
	seen = alive

	if !primed {
		return
	}

	for name, bytes := range added {
		sum := gathered[name]
		sum[0] += bytes[0]
		sum[1] += bytes[1]
		sum[2] = max(sum[2], secs)
		gathered[name] = sum
	}
}

// NetworkChanged — служба увидела сеть: ключ (пусто — не распознана) и вид.
// at — момент смены, мс.
func NetworkChanged(net, kind string, at int64) {
	mu.Lock()

	if net == netNow && kind == kindNow {
		mu.Unlock()

		return
	}

	before := changes
	changes++
	netNow, kindNow = net, kind
	on := target != ""

	mu.Unlock()

	if !on {
		return
	}

	go func() {
		flushBeforeChange(at, before)

		select {
		case wake <- struct{}{}:
		default:
		}
	}()
}

// flushBeforeChange — намеренное до смены сети уходит в старое место; трафик
// копился в старой сети — уходит туда даже без окна замеров.
func flushBeforeChange(at int64, before uint64) {
	collectMu.Lock()
	defer collectMu.Unlock()

	mu.Lock()
	path := target
	old := where
	where = nil
	since := pingsUntil
	mu.Unlock()

	if path == "" || old == nil || old.changes != before {
		advanceTo(at)
		dropTraffic()

		return
	}

	cut := at - changeGuardMs

	var (
		w  window
		ok bool
	)
	if cut > since {
		w, ok = readWindow(cut)
	} else {
		w, ok = takeTraffic(), true
	}

	advanceTo(at)

	if ok {
		record(path, old.place, w)
	}
}

func advanceTo(at int64) {
	mu.Lock()
	defer mu.Unlock()

	if at > pingsUntil {
		pingsUntil = at
	}
}

func dropTraffic() {
	mu.Lock()
	defer mu.Unlock()

	gathered = traffic{}
}

type window struct {
	nodes   map[string]NodeInfo
	pings   []sample
	traffic traffic
}

func (w window) empty() bool { return len(w.pings) == 0 && len(w.traffic) == 0 }

type sample struct {
	name  string
	at    int64
	delay uint64
}

type delays struct {
	at    []int64
	delay []uint64
}

func historyOf(p C.Proxy) delays {
	var out delays

	for _, record := range p.DelayHistory() {
		out.at = append(out.at, record.Time.UnixMilli())
		out.delay = append(out.delay, uint64(record.Delay))
	}

	return out
}

// histories — история задержек узлов из known: из списка ядра (узлы самой
// подписки) и из провайдеров — в списке ядра их нет.
func histories(known map[string]NodeInfo) map[string]delays {
	out := map[string]delays{}

	for name, p := range tunnel.Proxies() {
		if _, ok := known[name]; ok {
			out[name] = historyOf(p)
		}
	}

	for _, provider := range tunnel.Providers() {
		for _, p := range provider.Proxies() {
			name := p.Name()
			if _, ok := known[name]; !ok {
				continue
			}
			if _, taken := out[name]; !taken {
				out[name] = historyOf(p)
			}
		}
	}

	return out
}

// pingsOf — замеры в окне (since, until], мс; 0 — неудача.
func pingsOf(histories map[string]delays, since, until int64) []sample {
	var out []sample

	for name, history := range histories {
		for i, at := range history.at {
			if at > since && at <= until {
				out = append(out, sample{name: name, at: at, delay: history.delay[i]})
			}
		}
	}

	return out
}

// nextRead — через сколько читать снова, чтобы застать каждый замер: у узла с
// полной историей десять замеров уложились в span — читать вдвое чаще. Второе —
// сколько узлов, возможно, уже потеряли замеры с окна since.
func nextRead(histories map[string]delays, since int64) (time.Duration, int) {
	wait := tickMax
	overflowed := 0

	for _, history := range histories {
		if len(history.at) < historyCap {
			continue
		}

		oldest, newest := history.at[0], history.at[0]
		for _, at := range history.at {
			oldest = min(oldest, at)
			newest = max(newest, at)
		}

		if since > 0 && oldest > since {
			overflowed++
		}

		wait = min(wait, time.Duration(newest-oldest)*time.Millisecond/2)
	}

	return min(max(wait, tickMin), tickMax), overflowed
}

// readWindow — замеры с прошлого чтения по until (мс) и накопленный трафик.
func readWindow(until int64) (window, bool) {
	known := LoadedNodes()
	if len(known) == 0 {
		return window{}, false
	}

	listed := histories(known)

	mu.Lock()
	since := pingsUntil
	mu.Unlock()

	pings := pingsOf(listed, since, until)

	wait, overflowed := nextRead(listed, since)
	if overflowed > 0 {
		log.Debugln("[Report] %d node(s) were checked more often than read, reading every %s now", overflowed, wait)
	}

	mu.Lock()
	pingsUntil = until
	next = wait
	bytes := gathered
	gathered = traffic{}
	mu.Unlock()

	return window{nodes: known, pings: pings, traffic: bytes}, true
}

// takeTraffic — только накопленный трафик, без чтения ядра.
func takeTraffic() window {
	mu.Lock()
	defer mu.Unlock()

	bytes := gathered
	gathered = traffic{}

	return window{nodes: LoadedNodes(), traffic: bytes}
}

func record(path string, place Place, w window) {
	if w.empty() {
		return
	}

	now := time.Now().Unix()

	filesMu.Lock()
	defer filesMu.Unlock()

	store := Load(path)

	for _, ping := range w.pings {
		info := w.nodes[ping.name]
		key := NodeKey(info)
		store.RememberNode(key, info)
		store.AddPing(place, ping.at/1000, key, ping.delay)
	}

	for name, bytes := range w.traffic {
		info, ok := w.nodes[name]
		if !ok {
			continue
		}

		key := NodeKey(info)
		store.RememberNode(key, info)
		store.AddUse(place, now, key, Use{Up: bytes[0], Down: bytes[1], Sec: max(bytes[2], 1)})
	}

	store.Prune(now)

	if err := Save(path, store); err != nil {
		log.Warnln("[Report] the measurements were not saved: %s", err.Error())
	}
}

func locate(net, kind string, stamp uint64) *spot {
	ip4, ip6 := Address()

	return &spot{place: Place{Net: net, Kind: kind, IP4: ip4, IP6: ip6}, changes: stamp, ipAt: time.Now().Unix()}
}

// tick — прочитать окно и разложить в место. Запросы адреса — уже без замка
// сборщика: сайт отвечает долго, а служба ждёт замок считаные секунды.
func tick() {
	collectMu.Lock()

	mu.Lock()
	path := target
	before := changes
	mu.Unlock()

	if path == "" {
		collectMu.Unlock()

		return
	}

	w, ok := readWindow(nowMs())
	if !ok {
		collectMu.Unlock()

		return
	}

	mu.Lock()
	current := where
	after := changes
	net, kind := netNow, kindNow
	mu.Unlock()

	known := current != nil && current.changes == after && before == after && net != "" && net == current.place.Net
	if known {
		record(path, current.place, w)
	} else if !w.empty() {
		log.Debugln("[Report] %d measurement(s) dropped: the network they were made in is not known for sure", len(w.pings)+len(w.traffic))
	}

	collectMu.Unlock()

	if !known {
		if net == "" {
			return
		}

		fresh := locate(net, kind, after)

		mu.Lock()
		if changes == after && where == nil {
			where = fresh
		}
		mu.Unlock()

		return
	}

	now := time.Now().Unix()
	if !current.ipIsDue(now) {
		return
	}

	ip4, ip6 := Address()

	mu.Lock()
	defer mu.Unlock()

	if where != current {
		return
	}

	refreshed := *current
	// Не ответил сайт — прежний адрес остаётся: сеть та же.
	if ip4 != "" || ip6 != "" {
		refreshed.place.IP4, refreshed.place.IP6 = ip4, ip6
	}
	refreshed.ipAt = now
	where = &refreshed
}

// PlaceFor — место для замера в сети net: узнанное сборщиком, если сеть та
// же, иначе адрес спрашивается сейчас.
func PlaceFor(net, kind string) Place {
	mu.Lock()
	current := where
	same := current != nil && current.changes == changes && current.place.Net == net
	mu.Unlock()

	if same {
		return current.place
	}

	ip4, ip6 := Address()

	return Place{Net: net, Kind: kind, IP4: ip4, IP6: ip6}
}

// RecordFreeze — итоги проверки 16–20 в месте place.
func RecordFreeze(path string, place Place, known map[string]NodeInfo, verdicts map[string]Freeze) {
	now := time.Now().Unix()

	filesMu.Lock()
	defer filesMu.Unlock()

	store := Load(path)
	added := false

	for name, verdict := range verdicts {
		info, ok := known[name]
		if !ok {
			continue
		}

		at := verdict.At
		if at <= 0 {
			at = now
		}

		key := NodeKey(info)
		store.RememberNode(key, info)
		store.AddFreeze(place, at, key, verdict.Verdict, verdict.Status)
		added = true
	}

	if !added {
		return
	}

	store.Prune(now)

	if err := Save(path, store); err != nil {
		log.Warnln("[Report] the 16–20 results were not saved: %s", err.Error())
	}
}
