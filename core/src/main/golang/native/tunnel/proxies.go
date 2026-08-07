package tunnel

import (
	"sort"
	"strings"

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

func QueryProxyGroupNames(excludeNotSelectable bool) []string {
	mode := tunnel.Mode()

	if mode == tunnel.Direct {
		return []string{}
	}

	// Без ядра, поднявшего конфигурацию, группы `GLOBAL` не существует.
	// Экран спрашивает список групп на каждую перерисовку, в том числе
	// пока туннель не поднят, — без этой проверки был бы отказ в горутине
	// JNI, то есть падение всего приложения.
	root := tunnel.Proxies()["GLOBAL"]
	if root == nil {
		return []string{}
	}

	global, ok := root.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return []string{}
	}

	providers := global.Providers()
	if len(providers) == 0 {
		return []string{}
	}

	proxies := providers[0].Proxies()
	result := make([]string, 0, len(proxies)+1)

	if mode == tunnel.Global {
		result = append(result, "GLOBAL")
	}

	selectable := make([]string, 0, len(proxies)+1)
	selectable = append(selectable, result...)

	for _, p := range proxies {
		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}

		if g.Hidden() {
			continue
		}

		result = append(result, p.Name())

		// «Невыбираемая» группа — та, в которой узел нельзя закрепить руками,
		// то есть не реализующая `SelectAble` в mihomo
		// (`adapter/outboundgroup/util.go`). В этой версии ядра такая ровно
		// одна — балансировщик `load-balance` (тип `relay` из групп убран
		// вовсе). В списке групп он не нужен: открыть можно, а сделать
		// внутри нечего.
		//
		// Раньше здесь стояло `p.Type() == C.Selector`, и фильтр был
		// бесполезен: у подписок Remnawave основная группа почти всегда
		// `url-test`, и под таким условием она пропадала вместе
		// с балансировщиком. Поэтому флаг и держали выключенным.
		if _, ok := g.(outboundgroup.SelectAble); ok {
			selectable = append(selectable, p.Name())
		}
	}

	// Если выбирать не из чего вовсе (конфиг из одних балансировщиков),
	// отдаём что есть: показать группу, в которой нельзя выбрать узел,
	// лучше, чем пустой список — на пустой экран отвечает так же,
	// как на «ядро о группах ещё не знает», и человек видит «список
	// из файла подписки» при поднятом туннеле.
	if excludeNotSelectable && len(selectable) > 0 {
		return selectable
	}

	return result
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

func PatchSelector(selector, name string) bool {
	p := tunnel.Proxies()[selector]

	if p == nil {
		log.Warnln("Patch selector `%s`: not found", selector)

		return false
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	s, ok := g.(outboundgroup.SelectAble)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	if err := s.Set(name); err != nil {
		log.Warnln("Patch selector `%s`: %s", selector, err.Error())
	}

	log.Infoln("Patch selector %s -> %s", selector, name)

	closeConnByGroup(selector)

	return true
}

// convertProxies переводит узлы ядра в модель для интерфейса.
//
// `groupTestURL` — адрес, по которому эту группу проверяют. Задержка у узла
// хранится по адресу пробы (`Proxy.extra` в mihomo), и узел, входящий сразу
// в две группы с разными адресами, держит две записи. Раньше здесь бралась
// первая попавшаяся запись перебором map — а порядок обхода map в Go случайный,
// то есть на одном и том же экране задержка бралась то от одной группы,
// то от другой. Берём адрес своей группы, и только если по нему пробы
// ещё не было, соглашаемся на любую известную.
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
			// Порядок обхода map в Go случайный, поэтому берём не первый
			// попавшийся адрес, а наименьший: иначе на соседних перерисовках
			// у одного узла показывалась бы задержка то от одной группы,
			// то от другой.
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
