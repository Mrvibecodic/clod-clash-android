package report

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

const now = int64(1_800_000_000)

func node() NodeInfo {
	return NodeInfo{Name: "Узел", Type: "vless", Server: "Node.Example.com", Port: 443}
}

func at(ip4 string) Place {
	return Place{Net: "net0000000000000", Kind: "wifi", IP4: ip4}
}

func TestNodeKeyMatchesTheMiddleware(t *testing.T) {
	if got := NodeKey(node()); got != "5b9a8536deff" {
		t.Fatalf("ключ узла: %s", got)
	}
}

func TestDelaysFallIntoBuckets(t *testing.T) {
	for delay, want := range map[uint64]int{1: 0, 99: 0, 100: 1, 399: 2, 799: 3, 1499: 4, 5000: 5} {
		if got := bucketOf(delay); got != want {
			t.Fatalf("%d мс: корзина %d, ждали %d", delay, got, want)
		}
	}
}

func TestOnlyClosedHoursAreReportedAndDropped(t *testing.T) {
	store := &Store{}
	key := NodeKey(node())
	store.RememberNode(key, node())
	past := hourOf(now) - hour
	store.AddPing(at("203.0.113.7"), past+10, key, 150)
	store.AddPing(at("203.0.113.7"), past+20, key, 0)
	store.AddUse(at("203.0.113.7"), past+30, key, Use{Up: 10, Down: 20, Sec: 61})
	store.AddFreeze(at("203.0.113.7"), past+40, key, "ok", 200)
	store.AddPing(at("203.0.113.7"), now, key, 50)

	if oldest, ok := store.OldestClosed(now); !ok || oldest != past || store.HoursBefore(hourOf(now)) != 1 {
		t.Fatalf("закрытые часы: %d %v", oldest, ok)
	}

	raw, err := json.Marshal(store.Report(now, hourOf(now), "dev", "0.0.0"))
	if err != nil {
		t.Fatal(err)
	}

	var report struct {
		Platform string `json:"platform"`
		Nodes    map[string]NodeInfo
		Networks map[string]struct {
			Kind  string           `json:"kind"`
			Hours []map[string]any `json:"hours"`
		}
	}
	if err := json.Unmarshal(raw, &report); err != nil {
		t.Fatal(err)
	}

	hours := report.Networks["net0000000000000"].Hours
	if report.Platform != "android" || report.Networks["net0000000000000"].Kind != "wifi" || len(hours) != 1 {
		t.Fatalf("отчёт: %s", raw)
	}
	entry := hours[0]
	if entry["h"].(float64) != float64(past) || entry["ip4"] != "203.0.113.7" || entry["ip6"] != nil {
		t.Fatalf("час: %v", entry)
	}
	ping := entry["ping"].(map[string]any)[key].(map[string]any)
	if ping["n"].(float64) != 2 || ping["fail"].(float64) != 1 || ping["b"].([]any)[1].(float64) != 1 {
		t.Fatalf("пинг: %v", ping)
	}
	use := entry["use"].(map[string]any)[key].(map[string]any)
	if use["min"].(float64) != 2 || use["sec"] != nil {
		t.Fatalf("трафик: %v", use)
	}
	if entry["freeze"].(map[string]any)[key].(map[string]any)["status"].(float64) != 200 {
		t.Fatalf("16–20: %v", entry["freeze"])
	}
	if report.Nodes[key].Type != "vless" {
		t.Fatalf("узлы: %v", report.Nodes)
	}

	store.DropSent(now, hourOf(now))
	if _, left := store.OldestClosed(now); left || store.LastSent != now || store.Nodes[key].Name == "" {
		t.Fatal("после приёма остаётся только текущий час со своими узлами")
	}
}

func TestANewAddressWithinTheHourGetsItsOwnRecord(t *testing.T) {
	store := &Store{}
	past := hourOf(now) - hour
	store.AddPing(at("203.0.113.7"), past+10, "k", 100)
	store.AddPing(at("198.51.100.9"), past+900, "k", 0)
	store.AddPing(at("203.0.113.7"), past+1800, "k", 100)

	hours := store.Networks["net0000000000000"].Hours
	if len(hours) != 2 || hours[0].Ping["k"].N != 2 || hours[1].IP4 != "198.51.100.9" || hours[1].Ping["k"].Fail != 1 {
		t.Fatalf("записи часа: %+v %+v", hours[0], hours[1])
	}
}

func TestOldHoursAndOrphanNodesAreForgotten(t *testing.T) {
	store := &Store{}
	key := NodeKey(node())
	store.RememberNode(key, node())
	store.RememberNode("orphan", node())
	store.AddUse(at(""), now-keep-hour, key, Use{Up: 1, Down: 2, Sec: 300})
	store.Prune(now)

	if len(store.Networks) != 0 || len(store.Nodes) != 0 {
		t.Fatalf("осталось: %+v %+v", store.Networks, store.Nodes)
	}
}

func TestTimeOfUseStaysWithinTheHour(t *testing.T) {
	store := &Store{}
	for i := 0; i < 20; i++ {
		store.AddUse(at(""), now, "k", Use{Up: 1, Down: 1, Sec: 300})
	}

	entry := store.Networks["net0000000000000"].Hours[0]
	if entry.Use["k"].Sec != hour || entry.Use["k"].Up != 20 || entry.toReport()["use"].(map[string]any)["k"].(map[string]uint64)["min"] != 60 {
		t.Fatalf("трафик часа: %+v", entry.Use["k"])
	}
}

func TestTheWindowIsCountedInMillisecondsAndTakesEachPingOnce(t *testing.T) {
	ms := func(text string) int64 {
		parsed, err := time.Parse(time.RFC3339Nano, text)
		if err != nil {
			t.Fatal(err)
		}
		return parsed.UnixMilli()
	}
	histories := map[string]delays{
		"Узел": {
			at:    []int64{ms("2026-10-04T07:00:00.5Z"), ms("2026-10-04T10:04:00.250Z"), ms("2026-10-04T10:04:00.750Z")},
			delay: []uint64{120, 0, 80},
		},
	}
	until := ms("2026-10-04T10:04:00.5Z")

	first := pingsOf(histories, ms("2026-10-04T07:00:00.5Z"), until)
	if len(first) != 1 || first[0].delay != 0 {
		t.Fatalf("первое окно: %+v", first)
	}

	second := pingsOf(histories, until, ms("2026-10-04T10:05:00Z"))
	if len(second) != 1 || second[0].delay != 80 {
		t.Fatalf("второе окно: %+v", second)
	}
}

func TestANodeCheckedOftenIsReadOftenEnough(t *testing.T) {
	minute := int64(60_000)
	start := int64(1_000_000_000_000)

	often := delays{}
	for i := int64(0); i < 10; i++ {
		often.at = append(often.at, start+i*minute)
		often.delay = append(often.delay, 50)
	}
	rare := delays{at: []int64{start}, delay: []uint64{50}}

	wait, lost := nextRead(map[string]delays{"Частый": often, "Редкий": rare}, start+30_000)
	if wait != 270*time.Second || lost != 0 {
		t.Fatalf("чтение раз в %s, потеряно %d", wait, lost)
	}

	if _, lost := nextRead(map[string]delays{"Частый": often}, start-minute); lost != 1 {
		t.Fatal("окно раньше самого старого из десяти — часть могла уйти")
	}

	dense := delays{}
	for i := int64(0); i < 10; i++ {
		dense.at = append(dense.at, start+i*1000)
		dense.delay = append(dense.delay, 50)
	}
	if wait, _ := nextRead(map[string]delays{"Частый": dense}, 0); wait != tickMin {
		t.Fatalf("частые проверки: %s", wait)
	}
	if wait, _ := nextRead(map[string]delays{}, 0); wait != tickMax {
		t.Fatalf("без полных историй: %s", wait)
	}
}

func TestNodesComeFromTheSubscriptionList(t *testing.T) {
	got := NodesOf([]map[string]any{
		{"name": "А", "type": "VLESS", "server": "a.example.com", "port": 443},
		{"name": "Б", "type": "ss", "server": "b.example.com", "port": "8388"},
		{"name": "В", "type": "trojan", "server": "c.example.com", "port": float64(2053)},
		{"name": "Без порта", "type": "vless", "server": "d.example.com"},
		{"name": "", "type": "vless", "server": "e.example.com", "port": 1},
	})

	if len(got) != 3 || got["А"].Type != "vless" || got["Б"].Port != 8388 || got["В"].Port != 2053 {
		t.Fatalf("узлы: %+v", got)
	}
}

func TestTheAnswerIsAnAddressOfTheAskedFamily(t *testing.T) {
	cases := []struct {
		body string
		v6   bool
		want string
	}{
		{`"203.0.113.7"`, false, "203.0.113.7"},
		{"203.0.113.7\n", false, "203.0.113.7"},
		{`"2001:db8::7"`, true, "2001:db8::7"},
		{`"203.0.113.7"`, true, ""},
		{"<html>", false, ""},
		{"", false, ""},
	}

	for _, c := range cases {
		if got := ParseAddress(c.body, c.v6); got != c.want {
			t.Fatalf("%q: %q, ждали %q", c.body, got, c.want)
		}
	}
}

func TestAReportIsSentOnlyWhenDueAndKeptUntilAccepted(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sub.json")
	key := NodeKey(node())
	past := time.Now().Unix() - 2*hour

	store := &Store{}
	store.RememberNode(key, node())
	store.AddPing(at("203.0.113.7"), past, key, 100)
	if err := Save(path, store); err != nil {
		t.Fatal(err)
	}

	moment := time.Now().Unix()
	packed, ok := Prepare(path, moment, "0.0.0")
	if !ok || packed.Hours != 1 || packed.Until != hourOf(moment) {
		t.Fatalf("отчёт не собран: %v %+v", ok, packed)
	}

	reader, err := gzip.NewReader(bytes.NewReader(packed.Gz))
	if err != nil {
		t.Fatal(err)
	}
	raw, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	var sent map[string]any
	if err := json.Unmarshal(raw, &sent); err != nil || len(sent["dev"].(string)) != 32 {
		t.Fatalf("отчёт: %s", raw)
	}

	Sent(path, moment, packed.Until, 429)
	if _, ok := Prepare(path, moment+60, "0.0.0"); ok {
		t.Fatal("после ответа прослойки раньше 6 часов не шлём")
	}
	if Load(path).HoursBefore(hourOf(moment)) != 1 {
		t.Fatal("не принятое остаётся")
	}

	Sent(path, moment+sendEvery, packed.Until, 204)
	if Load(path).HoursBefore(hourOf(moment+sendEvery)) != 0 {
		t.Fatal("принятое удаляется")
	}
}

func backlog(hours int64, moment int64) *Store {
	store := &Store{}
	for i := int64(1); i <= hours; i++ {
		store.AddPing(at("203.0.113.7"), hourOf(moment)-i*hour, "k", 100)
	}

	return store
}

func TestAReportTakesTheOldestTwoDaysAndNothingNewerThanTheClosedHour(t *testing.T) {
	size := func(report map[string]any) ([]byte, error) { return json.Marshal(report) }

	store := backlog(7*24, now)
	oldest, _ := store.OldestClosed(now)
	packed, err := packWithin(store, now, oldest, "", "", reportMaxGz, size)
	if err != nil || packed.Until != oldest+sendWindow || packed.Hours != 48 {
		t.Fatalf("окно: %+v %v", packed, err)
	}

	// Завал меньше окна — граница на текущем часе, он сам не уходит.
	small := backlog(3, now)
	oldest, _ = small.OldestClosed(now)
	packed, err = packWithin(small, now, oldest, "", "", reportMaxGz, size)
	if err != nil || packed.Until != hourOf(now) || packed.Hours != 3 {
		t.Fatalf("малый завал: %+v %v", packed, err)
	}
}

func TestAReportThatDoesNotFitShrinksItsWindow(t *testing.T) {
	// «Сжатие» — по сто байт на запись часа: лимит в 10 записей.
	count := func(report map[string]any) ([]byte, error) {
		n := 0
		for _, network := range report["networks"].(map[string]any) {
			n += len(network.(map[string]any)["hours"].([]map[string]any))
		}

		return make([]byte, n*100), nil
	}

	store := backlog(7*24, now)
	oldest, _ := store.OldestClosed(now)
	packed, err := packWithin(store, now, oldest, "", "", 1000, count)
	if err != nil || packed.Hours != 6 || packed.Until != oldest+6*hour || len(packed.Gz) > 1000 {
		t.Fatalf("усечённое окно: %+v %v", packed, err)
	}

	// Даже одна запись не влезает — уходит всё равно она одна, не бесконечный цикл.
	packed, err = packWithin(store, now, oldest, "", "", 1, count)
	if err != nil || packed.Hours != 1 || packed.Until != oldest+hour {
		t.Fatalf("одна запись: %+v %v", packed, err)
	}
}

func TestProviderNodesComeFromPayloadAndFile(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "panel")
	if err := os.WriteFile(file, []byte("proxies:\n  - name: Чужой\n    type: VLESS\n    server: p.example.com\n    port: 443\n  - name: Свой\n    type: ss\n    server: q.example.com\n    port: 8388\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	providers := ProvidersOf(map[string]map[string]any{
		"panel":  {"type": "http", "url": "https://example.com/p", "path": file},
		"inline": {"type": "inline", "payload": []any{map[string]any{"name": "Встроенный", "type": "trojan", "server": "r.example.com", "port": 2053}}},
		"later":  {"type": "http", "path": filepath.Join(dir, "missing")},
	})
	own := NodesOf([]map[string]any{{"name": "Свой", "type": "vless", "server": "a.example.com", "port": 443}})

	got := AllNodes(own, providers)
	if len(got) != 3 || got["Чужой"].Type != "vless" || got["Встроенный"].Port != 2053 {
		t.Fatalf("узлы: %+v", got)
	}
	// Одноимённый узел подписки первее провайдерского.
	if got["Свой"].Server != "a.example.com" {
		t.Fatalf("одноимённый: %+v", got["Свой"])
	}

	// Файл перечитывается, когда менялся.
	if err := os.WriteFile(file, []byte("proxies:\n  - name: Новый\n    type: ss\n    server: n.example.com\n    port: 1\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	later := time.Now().Add(time.Minute)
	if err := os.Chtimes(file, later, later); err != nil {
		t.Fatal(err)
	}
	got = AllNodes(own, providers)
	if _, stale := got["Чужой"]; stale || got["Новый"].Port != 1 {
		t.Fatalf("после смены файла: %+v", got)
	}
}

// inside — сколько горутин пакета сейчас в функции fn.
func inside(fn string) int {
	buf := make([]byte, 1<<20)
	buf = buf[:runtime.Stack(buf, true)]

	n := 0
	for _, g := range strings.Split(string(buf), "\n\n") {
		if strings.Contains(g, "report."+fn+"(") {
			n++
		}
	}

	return n
}

func TestCollectionStopsAtOnceAndRestartsWithItsTrafficReading(t *testing.T) {
	dir := t.TempDir()
	// Цикл сбора и чтение соединений — по count штук, ждём не дольше двух секунд.
	settled := func(count int) bool {
		for deadline := time.Now().Add(2 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
			if inside("loop") == count && inside("trafficLoop") == count {
				return true
			}
		}

		return false
	}
	t.Cleanup(func() {
		SetTarget("")
		settled(0)
	})

	SetTarget(filepath.Join(dir, "a"))
	// Остановка и тут же запуск: сбор продолжается, один цикл и одно чтение.
	SetTarget("")
	SetTarget(filepath.Join(dir, "b"))
	if !settled(1) {
		t.Fatalf("после перезапуска: цикл %d, чтение соединений %d", inside("loop"), inside("trafficLoop"))
	}

	// Остановка кончает и цикл, и чтение сразу, а не после их ожидания.
	SetTarget("")
	if !settled(0) {
		t.Fatalf("после остановки идут: цикл %d, чтение соединений %d", inside("loop"), inside("trafficLoop"))
	}

	// Новый запуск после остановки читает и соединения.
	SetTarget(filepath.Join(dir, "c"))
	if !settled(1) {
		t.Fatalf("после нового запуска: цикл %d, чтение соединений %d", inside("loop"), inside("trafficLoop"))
	}
}

// Смена сети вскоре после чтения замеров отдаёт старому месту только трафик;
// сбор после неё жив, а не стоит на своём замке.
func TestANetworkChangeRightAfterAReadKeepsTheCollectorAlive(t *testing.T) {
	dir := t.TempDir()
	address := Address
	Address = func() (string, string) { return "192.0.2.1", "" }
	SetNodes(map[string]NodeInfo{"Узел": node()}, nil)

	alive := func() bool {
		done := make(chan struct{})
		go func() {
			LoadedNodes()
			close(done)
		}()

		select {
		case <-done:
			return true
		case <-time.After(2 * time.Second):
			return false
		}
	}
	t.Cleanup(func() {
		if !alive() {
			return
		}

		SetTarget("")
		NetworkChanged("", "", time.Now().UnixMilli())
		SetNodes(nil, nil)
		Address = address
	})

	SetTarget(filepath.Join(dir, "s"))

	// Первая сеть: сразу чтение замеров и место (сеть с адресом).
	NetworkChanged("net-a", "wifi", time.Now().UnixMilli())
	known := func() bool {
		mu.Lock()
		defer mu.Unlock()

		return where != nil && where.place.Net == "net-a"
	}
	for deadline := time.Now().Add(2 * time.Second); !known(); time.Sleep(10 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("место первой сети не узнано")
		}
	}

	// Вторая сеть — через доли секунды после того чтения. Сброс перед ней
	// сдвигает окно до момента смены; встал на замке — окно не узнать вовсе.
	changed := time.Now().UnixMilli()
	NetworkChanged("net-b", "mobile", changed)
	flushed := func() (bool, bool) {
		answer := make(chan bool, 1)
		go func() {
			mu.Lock()
			defer mu.Unlock()

			answer <- pingsUntil >= changed
		}()

		select {
		case done := <-answer:
			return done, true
		case <-time.After(2 * time.Second):
			return false, false
		}
	}
	for deadline := time.Now().Add(2 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		done, ok := flushed()
		if !ok {
			t.Fatal("сбор встал на своём замке после смены сети вскоре после чтения")
		}
		if done {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("сброс перед сменой сети не прошёл")
		}
	}

	if !alive() {
		t.Fatal("сбор встал на своём замке после смены сети вскоре после чтения")
	}
}
