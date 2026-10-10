package report

import (
	"os"
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
// Кончается окно на lagMs раньше чтения: часть замеров ядро кладёт в историю
// позже, чем помечает.
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
	// Окно замеров кончается на столько раньше чтения. Clod Core кладёт неудачу
	// пробы, которую спасла повторная, в историю, когда та ответила, а помечает
	// началом первой: позже не больше чем на 1,25 с и тайм-аут проверки группы
	// (5 с по умолчанию). Больше не надо: история узла — 10 записей, и лишнее
	// отставание теряло бы замеры у часто проверяемых узлов.
	lagMs = 20 * 1000
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

// ipIsDue — пора ли переспросить адрес; часы ушли назад — пора, а не ждать,
// пока догонят.
func (s *spot) ipIsDue(now int64) bool {
	if s.ipAt > now {
		return true
	}

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

	changed := path != target
	if changed {
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

	if changed {
		// Накопленное прежней подписки — в файл, из памяти вон.
		forget()
	}

	if start {
		go loop()
	} else if changed && path == "" {
		// Сбор выключен: цикл и чтение соединений кончаются сразу, а не после
		// ожидания цикла (до пяти минут), и новый SetTarget запускает их заново
		poke()
	}
}

func poke() {
	select {
	case wake <- struct{}{}:
	default:
	}
}

// loop — цикл сбора; чтение соединений идёт рядом и кончается вместе с ним.
func loop() {
	stop := make(chan struct{})
	defer close(stop)

	go trafficLoop(stop)

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

func trafficLoop(stop <-chan struct{}) {
	for {
		select {
		case <-time.After(trafficTick):
		case <-stop:
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
	reading := target
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

	// Пока шло чтение, сбор переключился на другую подписку (или
	// остановился): прирост принадлежит прежней, чужую копилку он не трогает
	if target != reading {
		return
	}

	secs := uint64(1)
	if readAt > 0 {
		secs = uint64(min(max((now-readAt)/1000, 1), int64(trafficTick/time.Second)*3))
	}
	readAt = now
	seen = alive

	if !primed {
		return
	}

	gather(gathered, added, secs)
}

// gather — прирост одного чтения в копилку: байты и secs секунд с трафиком
// каждому узлу, через который он шёл, — раз за чтение, сколько бы соединений
// через узел ни было.
func gather(into traffic, added map[string][2]uint64, secs uint64) {
	for name, bytes := range added {
		sum := into[name]
		sum[0] += bytes[0]
		sum[1] += bytes[1]
		sum[2] += secs
		into[name] = sum
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

		poke()
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

	// Раньше среза история уже полна: позже помеченное ядро могло ещё не положить.
	cut := at - max(changeGuardMs, lagMs)

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
// подписки) и из провайдеров — в списке ядра их нет — у того же узла, чей адрес
// в known: одноимённый узел другого провайдера не в счёт.
func histories(known map[string]NodeInfo) map[string]delays {
	out := map[string]delays{}

	for name, p := range tunnel.Proxies() {
		if info, ok := known[name]; ok && info.Provider == "" {
			out[name] = historyOf(p)
		}
	}

	for provider, listed := range tunnel.Providers() {
		for _, p := range listed.Proxies() {
			name := p.Name()
			if info, ok := known[name]; ok && info.Provider == provider {
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
// полной историей десять замеров уложились в span, а окно отстаёт на lagMs —
// читать вдвое чаще, чем span без него. Второе —
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

		wait = min(wait, time.Duration(newest-oldest-lagMs)*time.Millisecond/2)
	}

	return min(max(wait, tickMin), tickMax), overflowed
}

// readWindow — замеры с прошлого чтения по until (мс; окно назад не
// сдвигается) и накопленный трафик.
func readWindow(until int64) (window, bool) {
	known := LoadedNodes()
	if len(known) == 0 {
		return window{}, false
	}

	listed := histories(known)

	mu.Lock()
	since := pingsUntil
	mu.Unlock()

	until = max(until, since)

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

// takeTraffic — только накопленный трафик, без чтения ядра. Узлы берутся до
// замка: LoadedNodes берёт его сам.
func takeTraffic() window {
	known := LoadedNodes()

	mu.Lock()
	defer mu.Unlock()

	bytes := gathered
	gathered = traffic{}

	return window{nodes: known, traffic: bytes}
}

func record(path string, place Place, w window) {
	if w.empty() {
		return
	}

	now := time.Now().Unix()

	withStore(path, now, false, func(store *Store) {
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
	})
}

func locate(net, kind string, stamp uint64) *spot {
	ip4, ip6 := Address()

	return &spot{place: Place{Net: net, Kind: kind, IP4: ip4, IP6: ip6}, changes: stamp, ipAt: time.Now().Unix()}
}

// ipWork — что делать с адресом после чтения: узнать место заново или
// переспросить адрес места.
type ipWork struct {
	locate    bool
	net, kind string
	stamp     uint64
	refresh   *spot
}

// collectLocked — прочитать окно (по lagMs назад от нынешнего) и разложить в
// место; под collectMu. Запросы адреса — уже без замка сборщика: сайт отвечает
// долго, а служба ждёт замок считаные секунды.
func collectLocked() ipWork {
	mu.Lock()
	path := target
	before := changes
	// Часы ушли назад: окно — от нынешнего момента, иначе замеров не было бы,
	// пока часы не догонят прежнее.
	if now := nowMs(); pingsUntil > now {
		pingsUntil = now
	}
	mu.Unlock()

	if path == "" {
		return ipWork{}
	}

	w, ok := readWindow(nowMs() - lagMs)
	if !ok {
		return ipWork{}
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

	switch {
	case !known && net != "":
		return ipWork{locate: true, net: net, kind: kind, stamp: after}
	case known && current.ipIsDue(time.Now().Unix()):
		return ipWork{refresh: current}
	}

	return ipWork{}
}

// do — узнать место или переспросить адрес места.
func (work ipWork) do() {
	if work.locate {
		fresh := locate(work.net, work.kind, work.stamp)

		mu.Lock()
		if changes == work.stamp && where == nil {
			where = fresh
		}
		mu.Unlock()

		return
	}

	if work.refresh == nil {
		return
	}

	ip4, ip6 := Address()
	now := time.Now().Unix()

	mu.Lock()
	defer mu.Unlock()

	if where != work.refresh {
		return
	}

	refreshed := *work.refresh
	// Не ответил сайт — прежний адрес остаётся: сеть та же.
	if ip4 != "" || ip6 != "" {
		refreshed.place.IP4, refreshed.place.IP6 = ip4, ip6
	}
	refreshed.ipAt = now
	where = &refreshed
}

func tick() {
	collectMu.Lock()
	work := collectLocked()
	collectMu.Unlock()

	work.do()
}

// closedBefore — граница закрытых часов накопленного path: в часы раньше неё
// уже ничего не ляжет. Замер задержки ложится в час, когда сделан, но не
// раньше прочитанного окна (pingsUntil), всё прочее — в час, когда записано.
// Окно прочитано у того, что собирается, — граница по нему; у остального —
// нынешний час. Под collectMu: чтение, начатое до неё, уже разложено.
func closedBefore(path string, now int64) int64 {
	mu.Lock()
	defer mu.Unlock()

	if path != "" && path == target {
		return hourOf(min(pingsUntil/1000, now))
	}

	return hourOf(now)
}

// Forget — накопленное path больше не нужно (прослойка не принимает отчёты,
// канал выключен, подписка удалена): сбор по нему кончается, из памяти и с
// диска оно уходит и в файл не возвращается.
func Forget(path string) {
	if path == "" {
		return
	}

	collectMu.Lock()
	defer collectMu.Unlock()

	mu.Lock()
	stop := target == path
	if stop {
		target = ""
		where = nil
		gathered = traffic{}
	}
	mu.Unlock()

	cacheMu.Lock()
	if cachePath == path {
		cache = nil
		cachePath = ""
		cacheDirty = false
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		log.Warnln("[Report] the measurements were not removed: %s", err.Error())
	}
	cacheMu.Unlock()

	if stop {
		poke()
	}
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

// RecordFreeze — итоги проверки 16–20 в месте place. Итог ложится в час, когда
// записан: в отправленный час уже ничего не ложится.
func RecordFreeze(path string, place Place, known map[string]NodeInfo, verdicts map[string]Freeze) {
	now := time.Now().Unix()

	withStore(path, now, true, func(store *Store) {
		for name, verdict := range verdicts {
			info, ok := known[name]
			if !ok {
				continue
			}

			key := NodeKey(info)
			store.RememberNode(key, info)
			store.AddFreeze(place, now, key, verdict.Verdict, verdict.Status)
		}

		store.Prune(now)
	})
}
