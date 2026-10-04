// Package report — отчёт прослойке о качестве узлов: что ядро намерило
// (задержки, трафик через узлы), итоги проверки 16–20, вид сети и внешний
// адрес клиента, где сделан замер. Форма отчёта та же, что у ПК и что ждёт
// прослойка (lib/reports.php).
package report

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

const (
	// Старше этого накопленное не хранится и не отправляется.
	keep = 7 * 24 * 60 * 60
	hour = 60 * 60
)

// Границы корзин задержки, мс: <100, <200, <400, <800, <1500, остальное.
var bucketEdges = [...]uint64{100, 200, 400, 800, 1500}

const buckets = len(bucketEdges) + 1

// NodeInfo — узел, как его узнаёт прослойка: тип, адрес и порт из подписки.
type NodeInfo struct {
	Name   string `json:"name"`
	Type   string `json:"type"`
	Server string `json:"server"`
	Port   int    `json:"port"`
}

type Ping struct {
	N    uint64          `json:"n"`
	Fail uint64          `json:"fail"`
	B    [buckets]uint64 `json:"b"`
}

type Use struct {
	Up   uint64 `json:"up"`
	Down uint64 `json:"down"`
	// Секунды с трафиком через узел за этот час; в отчёт — минутами.
	Sec uint64 `json:"sec"`
}

type Freeze struct {
	Verdict string `json:"verdict"`
	Status  int    `json:"status"`
	At      int64  `json:"at"`
}

// Place — где сделан замер: сеть, её вид и внешний адрес клиента в ней.
type Place struct {
	Net  string
	Kind string
	IP4  string
	IP6  string
}

// Hour — час в одной сети с одним внешним адресом. Сменился адрес внутри
// часа — у того же часа вторая запись.
type Hour struct {
	H      int64             `json:"h"`
	IP4    string            `json:"ip4,omitempty"`
	IP6    string            `json:"ip6,omitempty"`
	Ping   map[string]*Ping  `json:"ping,omitempty"`
	Use    map[string]*Use   `json:"use,omitempty"`
	Freeze map[string]Freeze `json:"freeze,omitempty"`
}

type Network struct {
	Kind  string  `json:"kind"`
	Hours []*Hour `json:"hours"`
}

type Store struct {
	// Когда прослойка в последний раз ответила на отчёт каналом (любым кодом).
	LastTry int64 `json:"last_try"`
	// Когда отчёт в последний раз был принят.
	LastSent int64               `json:"last_sent"`
	Nodes    map[string]NodeInfo `json:"nodes"`
	Networks map[string]*Network `json:"networks"`
}

func hourOf(at int64) int64 {
	r := at % hour
	if r < 0 {
		r += hour
	}

	return at - r
}

// NodeKey — ключ узла для прослойки: тот же расчёт, что у неё (rep_node_key).
func NodeKey(info NodeInfo) string {
	sum := sha256.Sum256([]byte(info.Type + "|" + strings.ToLower(info.Server) + "|" + strconv.Itoa(info.Port)))

	return hex.EncodeToString(sum[:])[:12]
}

func bucketOf(delay uint64) int {
	for i, edge := range bucketEdges {
		if delay < edge {
			return i
		}
	}

	return len(bucketEdges)
}

func (h *Hour) hasData() bool {
	return len(h.Ping) > 0 || len(h.Use) > 0 || len(h.Freeze) > 0
}

func (h *Hour) keys() []string {
	var out []string
	for key := range h.Ping {
		out = append(out, key)
	}
	for key := range h.Use {
		out = append(out, key)
	}
	for key := range h.Freeze {
		out = append(out, key)
	}

	return out
}

func (s *Store) hourAt(place Place, at int64) *Hour {
	if s.Networks == nil {
		s.Networks = map[string]*Network{}
	}

	network := s.Networks[place.Net]
	if network == nil {
		network = &Network{}
		s.Networks[place.Net] = network
	}
	network.Kind = place.Kind

	h := hourOf(at)
	for _, entry := range network.Hours {
		if entry.H == h && entry.IP4 == place.IP4 && entry.IP6 == place.IP6 {
			return entry
		}
	}

	entry := &Hour{H: h, IP4: place.IP4, IP6: place.IP6}
	network.Hours = append(network.Hours, entry)

	return entry
}

func (s *Store) RememberNode(key string, info NodeInfo) {
	if s.Nodes == nil {
		s.Nodes = map[string]NodeInfo{}
	}

	s.Nodes[key] = info
}

// AddPing — один замер задержки: 0 — неудача.
func (s *Store) AddPing(place Place, at int64, key string, delay uint64) {
	entry := s.hourAt(place, at)
	if entry.Ping == nil {
		entry.Ping = map[string]*Ping{}
	}

	ping := entry.Ping[key]
	if ping == nil {
		ping = &Ping{}
		entry.Ping[key] = ping
	}

	ping.N++
	if delay == 0 {
		ping.Fail++
	} else {
		ping.B[bucketOf(delay)]++
	}
}

func (s *Store) AddUse(place Place, at int64, key string, delta Use) {
	entry := s.hourAt(place, at)
	if entry.Use == nil {
		entry.Use = map[string]*Use{}
	}

	used := entry.Use[key]
	if used == nil {
		used = &Use{}
		entry.Use[key] = used
	}

	used.Up += delta.Up
	used.Down += delta.Down
	used.Sec = min(used.Sec+delta.Sec, hour)
}

func (s *Store) AddFreeze(place Place, at int64, key, verdict string, status int) {
	entry := s.hourAt(place, at)
	if entry.Freeze == nil {
		entry.Freeze = map[string]Freeze{}
	}

	entry.Freeze[key] = Freeze{Verdict: verdict, Status: status, At: at}
}

// Prune — выбросить старше keep, пустые часы и сети и узлы, на которые никто
// не ссылается.
func (s *Store) Prune(now int64) {
	oldest := hourOf(now - keep)
	used := map[string]bool{}

	for net, network := range s.Networks {
		kept := network.Hours[:0]
		for _, entry := range network.Hours {
			if entry.H >= oldest && entry.hasData() {
				kept = append(kept, entry)
				for _, key := range entry.keys() {
					used[key] = true
				}
			}
		}
		network.Hours = kept

		if len(kept) == 0 {
			delete(s.Networks, net)
		}
	}

	for key := range s.Nodes {
		if !used[key] {
			delete(s.Nodes, key)
		}
	}
}

// OldestClosed — самый старый закрытый час (тот, что уже не пополнится);
// false — отправлять нечего.
func (s *Store) OldestClosed(now int64) (int64, bool) {
	current := hourOf(now)
	oldest, found := int64(0), false

	for _, network := range s.Networks {
		for _, entry := range network.Hours {
			if entry.H < current && (!found || entry.H < oldest) {
				oldest, found = entry.H, true
			}
		}
	}

	return oldest, found
}

// HoursBefore — сколько записей часов раньше until.
func (s *Store) HoursBefore(until int64) int {
	count := 0

	for _, network := range s.Networks {
		for _, entry := range network.Hours {
			if entry.H < until {
				count++
			}
		}
	}

	return count
}

func (h *Hour) toReport() map[string]any {
	out := map[string]any{"h": h.H}

	if h.IP4 != "" {
		out["ip4"] = h.IP4
	}
	if h.IP6 != "" {
		out["ip6"] = h.IP6
	}
	if len(h.Ping) > 0 {
		out["ping"] = h.Ping
	}
	if len(h.Use) > 0 {
		used := map[string]any{}
		for key, use := range h.Use {
			used[key] = map[string]uint64{"up": use.Up, "down": use.Down, "min": min((use.Sec+59)/60, 60)}
		}
		out["use"] = used
	}
	if len(h.Freeze) > 0 {
		out["freeze"] = h.Freeze
	}

	return out
}

// Report — отчёт из часов раньше until (закрытых) в форме, которую ждёт прослойка.
func (s *Store) Report(now, until int64, dev, client string) map[string]any {
	networks := map[string]any{}
	nodes := map[string]NodeInfo{}
	from := int64(-1)

	for net, network := range s.Networks {
		var closed []*Hour
		for _, entry := range network.Hours {
			if entry.H < until {
				closed = append(closed, entry)
			}
		}

		if len(closed) == 0 {
			continue
		}

		sort.SliceStable(closed, func(i, j int) bool { return closed[i].H < closed[j].H })

		hours := make([]map[string]any, 0, len(closed))
		for _, entry := range closed {
			if from < 0 || entry.H < from {
				from = entry.H
			}
			for _, key := range entry.keys() {
				if info, ok := s.Nodes[key]; ok {
					nodes[key] = info
				}
			}
			hours = append(hours, entry.toReport())
		}

		networks[net] = map[string]any{"kind": network.Kind, "hours": hours}
	}

	if from < 0 {
		from = now
	}

	return map[string]any{
		"v":        1,
		"platform": "android",
		"client":   client,
		"dev":      dev,
		"from":     from,
		"to":       now,
		"nodes":    nodes,
		"networks": networks,
	}
}

// DropSent — отчёт принят: отправленные часы (раньше until) уходят, остальное остаётся.
func (s *Store) DropSent(now, until int64) {
	for _, network := range s.Networks {
		kept := network.Hours[:0]
		for _, entry := range network.Hours {
			if entry.H >= until {
				kept = append(kept, entry)
			}
		}
		network.Hours = kept
	}

	s.LastSent = now
	s.Prune(now)
}

// Load — накопленное из файла; нет файла или он испорчен — пустое.
func Load(path string) *Store {
	store := &Store{}

	raw, err := os.ReadFile(path)
	if err != nil {
		return store
	}

	if json.Unmarshal(raw, store) != nil {
		return &Store{}
	}

	return store
}

func Save(path string, store *Store) error {
	raw, err := json.Marshal(store)
	if err != nil {
		return err
	}

	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}

	fresh := path + ".new"
	if err := os.WriteFile(fresh, raw, 0600); err != nil {
		return err
	}

	return os.Rename(fresh, path)
}
