package tunnel

import (
	"net/url"
	"sort"
	"strings"

	"cfa/native/config/groups"

	"github.com/dlclark/regexp2"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

type SortMode int

const (
	Default SortMode = iota
	Title
	Delay
)

type Proxy struct {
	Name     string `json:"name"`
	Title    string `json:"title"`
	Subtitle string `json:"subtitle"`
	Type     string `json:"type"`
	Delay    int    `json:"delay"`
	IsGroup  bool   `json:"isGroup"`
}

type ProxyGroup struct {
	Type    string   `json:"type"`
	Now     string   `json:"now"`
	Proxies []*Proxy `json:"proxies"`
}

type sortableProxyList struct {
	list []*Proxy
	less func(a, b *Proxy) bool
}

func (s *sortableProxyList) Len() int {
	return len(s.list)
}

func (s *sortableProxyList) Less(i, j int) bool {
	return s.less(s.list[i], s.list[j])
}

func (s *sortableProxyList) Swap(i, j int) {
	s.list[i], s.list[j] = s.list[j], s.list[i]
}

type ProxyGroupNames struct {
	Direct bool              `json:"direct"`
	Names  []string          `json:"names"`
	Icons  map[string]string `json:"icons"`
	Main   string            `json:"main,omitempty"`
}

func QueryProxyGroupNames(excludeNotSelectable bool) *ProxyGroupNames {
	mode := tunnel.Mode()

	result := &ProxyGroupNames{
		Direct: mode == tunnel.Direct,
		Names:  []string{},
		Icons:  map[string]string{},
	}

	if mode == tunnel.Direct {
		return result
	}

	root := tunnel.Proxies()["GLOBAL"]
	if root == nil {
		return result
	}

	global, ok := root.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return result
	}

	if mode == tunnel.Global {
		result.Names = []string{"GLOBAL"}
		result.Main = groups.Main(result.Names, matchTarget())

		return result
	}

	providers := global.Providers()
	if len(providers) == 0 {
		return result
	}

	proxies := providers[0].Proxies()
	all := make([]string, 0, len(proxies))
	selectable := make([]string, 0, len(proxies))
	candidates := make([]groups.Candidate, 0, len(proxies))
	icons := make(map[string]string)

	for _, p := range proxies {
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}

		if g.Hidden() {
			continue
		}

		all = append(all, p.Name())

		if icon := httpsIcon(g.Icon()); icon != "" {
			icons[p.Name()] = icon
		}

		_, canSelect := g.(outboundgroup.SelectAble)
		if canSelect {
			selectable = append(selectable, p.Name())
		}

		candidates = append(candidates, groups.Candidate{Name: p.Name(), Selectable: canSelect})
	}

	names := all
	if excludeNotSelectable && len(selectable) > 0 {
		names = selectable
	}

	for _, name := range names {
		if icon, ok := icons[name]; ok {
			result.Icons[name] = icon
		}
	}

	result.Names = names
	result.Main = groups.MainOf(candidates, matchTarget())

	return result
}

func matchTarget() string {
	for _, rule := range tunnel.Rules() {
		if rule.RuleType() == C.MATCH {
			return rule.Adapter()
		}
	}

	return ""
}

func QueryProxyGroup(name string, sortMode SortMode, uiSubtitlePattern *regexp2.Regexp) *ProxyGroup {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Query group `%s`: not found", name)

		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Query group `%s`: invalid type %s", name, p.Type().String())

		return nil
	}

	proxies := convertProxies(g.Proxies(), uiSubtitlePattern, GroupTestURL(g))

	switch sortMode {
	case Title:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				return strings.Compare(a.Title, b.Title) < 0
			},
		}

		sort.Sort(wrapper)
	case Delay:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				return a.Delay < b.Delay
			},
		}

		sort.Sort(wrapper)
	case Default:
	default:
	}

	return &ProxyGroup{
		Type:    g.Type().String(),
		Now:     g.Now(),
		Proxies: proxies,
	}
}

// Исходы PatchSelector. Нулевое значение — «не получилось, запомненный выбор
// не трогать»: его же отдаёт мост, если вызов упал.
const (
	PatchFailed     = 0
	PatchDone       = 1
	PatchNoSelector = 2
)

func PatchSelector(selector, name string) int {
	p := tunnel.Proxies()[selector]

	if p == nil {
		log.Warnln("Patch selector `%s`: not found", selector)

		return PatchNoSelector
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return PatchNoSelector
	}

	s, ok := g.(outboundgroup.SelectAble)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return PatchNoSelector
	}

	if err := s.Set(name); err != nil {
		log.Warnln("Patch selector `%s`: %s", selector, err.Error())

		return PatchFailed
	}

	log.Infoln("Patch selector %s -> %s", selector, name)

	closeConnByGroup(selector)

	return PatchDone
}

func httpsIcon(raw string) string {
	value := strings.TrimSpace(raw)
	if value == "" {
		return ""
	}

	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil {
		return ""
	}

	return parsed.String()
}

func convertProxies(proxies []C.Proxy, uiSubtitlePattern *regexp2.Regexp, groupTestURL string) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range proxies {
		name := p.Name()
		title := name
		subtitle := p.Type().String()

		if uiSubtitlePattern != nil {
			if _, ok := p.Adapter().(outboundgroup.ProxyGroup); !ok {
				runes := []rune(name)
				match, err := uiSubtitlePattern.FindRunesMatch(runes)
				if err == nil && match != nil {
					title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
					subtitle = string(runes[match.Index : match.Index+match.Length])
				}
			}
		}

		testURL := groupTestURL
		if testURL == "" {
			testURL = C.DefaultTestURL
		}

		histories := p.ExtraDelayHistories()
		if _, ok := histories[testURL]; !ok {
			fallback := ""

			for k := range histories {
				if len(k) > 0 && (fallback == "" || k < fallback) {
					fallback = k
				}
			}

			if fallback != "" {
				testURL = fallback
			}
		}

		_, isGroup := p.Adapter().(outboundgroup.ProxyGroup)

		result = append(result, &Proxy{
			Name:     name,
			Title:    strings.TrimSpace(title),
			Subtitle: strings.TrimSpace(subtitle),
			Type:     p.Type().String(),
			Delay:    int(p.LastDelayForTestUrl(testURL)),
			IsGroup:  isGroup,
		})
	}
	return result
}
