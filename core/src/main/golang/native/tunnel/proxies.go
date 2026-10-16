package tunnel

import (
	"errors"
	"net/url"
	"strings"

	"cfa/native/config"
	"cfa/native/config/groups"
	"cfa/native/config/panel"

	"github.com/dlclark/regexp2"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

type Proxy struct {
	Name     string `json:"name"`
	Title    string `json:"title"`
	Subtitle string `json:"subtitle"`
	Type     string `json:"type"`
	Delay    int    `json:"delay"`
	IsGroup  bool   `json:"isGroup"`
	// Current — у группы: сервер, на котором она стоит (по цепочке вложенных
	// групп, без нового выбора); пусто — такого нет (см. shownNode). Так экран
	// видит сервер и за группой, которой нет в списке групп (скрытой в шаблоне).
	// CurrentGroup — группа, в которой этот сервер выбран
	Current      string `json:"current,omitempty"`
	CurrentTitle string `json:"currentTitle,omitempty"`
	CurrentGroup string `json:"currentGroup,omitempty"`
}

type ProxyGroup struct {
	Type    string   `json:"type"`
	Now     string   `json:"now"`
	Proxies []*Proxy `json:"proxies"`
	// Pinned — узел, закреплённый вручную в url-test/fallback; пусто, если
	// группа выбирает сама
	Pinned string `json:"pinned"`
	// AllHidden — все серверы группы только для мобильной сети, а сеть не
	// мобильная: серверов нет, группа отказывает
	AllHidden bool `json:"allHidden,omitempty"`
}

type ProxyGroupNames struct {
	Direct bool              `json:"direct"`
	Names  []string          `json:"names"`
	Icons  map[string]string `json:"icons"`
	Main   string            `json:"main,omitempty"`
	// Profile — подписка, чьи это группы: берётся у ядра вместе с ними, а не
	// отдельным запросом, и экран не подпишет группы чужой подпиской
	Profile string `json:"profile,omitempty"`
}

func QueryProxyGroupNames() *ProxyGroupNames {
	seq := config.ApplySeq()

	mode := tunnel.Mode()

	result := &ProxyGroupNames{
		Direct:  mode == tunnel.Direct,
		Names:   []string{},
		Icons:   map[string]string{},
		Profile: config.LoadedProfile(),
	}

	// Конфиг сменился, пока собирался список, — чьи это группы, неизвестно
	defer func() {
		if seq%2 == 1 || config.ApplySeq() != seq {
			result.Profile = ""
		}
	}()

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
	if len(selectable) > 0 {
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

// shownMember — узел, который группа использует, для экрана. У fallback — без
// снятия закрепления на мёртвом узле: это делает только соединение. У
// url-test — закреплённый узел, пока он жив (его возьмёт следующее
// соединение), иначе текущий, пока он жив и в группе, — без нового выбора;
// мёртвый или пропавший текущий и отсутствие текущего (до первого соединения)
// показываются тем, что группа выберет при следующем соединении. Так же
// показывает url-test ядро в /proxies. choose=false — без этого выбора: он
// закрепляется за группой и включает её в фоновые пробы текущих узлов
func shownMember(g outboundgroup.ProxyGroup, choose bool) string {
	c, ok := g.(outboundgroup.Pinnable)
	if !ok {
		return g.Now()
	}

	if g.Type() == C.URLTest {
		url, _ := g.(outboundgroup.CheckOptions).TestOptions()
		proxies := g.Proxies()
		usable := func(name string) bool {
			for _, p := range proxies {
				if p.Name() == name {
					return p.AliveForTestUrl(url)
				}
			}

			return false
		}

		if pinned := c.Pinned(); pinned != "" && usable(pinned) {
			return pinned
		}

		if current := c.CurrentNode(); current != nil && usable(current.Name()) {
			return current.Name()
		}

		if !choose {
			return ""
		}

		return g.Now()
	}

	if current := c.CurrentNode(); current != nil {
		return current.Name()
	}

	return g.Now()
}

// shownNode — узел, на котором группа стоит и который показывает выбранным
// (по цепочке вложенных групп), без нового выбора, и группа, где он лежит.
// Узел — объект, как в ядре: тёзка от другого провайдера — другой сервер.
// nil — такого узла нет: load-balance, петля, все серверы скрыты, url-test или
// fallback без текущего, с мёртвым текущим или с закреплённым не на текущем
func shownNode(g outboundgroup.ProxyGroup) (C.Proxy, outboundgroup.ProxyGroup) {
	for depth := 0; depth < 16; depth++ {
		if outboundgroup.AllHidden(g.Proxies()) {
			return nil, nil
		}

		member := currentOf(g)
		if member == nil || member.Name() != shownMember(g, false) {
			return nil, nil
		}

		if _, pinnable := g.(outboundgroup.Pinnable); pinnable && !member.AliveForTestUrl(GroupTestURL(g)) {
			return nil, nil
		}

		inner, isGroup := member.Adapter().(outboundgroup.ProxyGroup)
		if !isGroup {
			return member, g
		}

		g = inner
	}

	return nil, nil
}

// shownDelay — задержка px, как её показывает список: адресом url, а если им
// не мерили — первым по алфавиту из прочих. Не мерили вовсе — 0 (интерфейс
// покажет «—»); 0xffff ядро отдаёт и мёртвому, и не промеренному
func shownDelay(px C.Proxy, url string) int {
	histories := px.ExtraDelayHistories()

	if _, ok := histories[url]; !ok {
		fallback := ""

		for k := range histories {
			if len(k) > 0 && (fallback == "" || k < fallback) {
				fallback = k
			}
		}

		if fallback == "" {
			return 0
		}

		url = fallback
	}

	return int(px.LastDelayForTestUrl(url))
}

func QueryProxyGroup(name string, uiSubtitlePattern *regexp2.Regexp) *ProxyGroup {
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

	members := g.Proxies()
	if outboundgroup.AllHidden(members) {
		return &ProxyGroup{Type: g.Type().String(), Proxies: []*Proxy{}, AllHidden: true}
	}

	proxies := convertProxies(members, uiSubtitlePattern, GroupTestURL(g), g.Type() == C.Selector)

	group := &ProxyGroup{
		Type:    g.Type().String(),
		Now:     shownMember(g, true),
		Proxies: proxies,
	}

	if pin, ok := g.(outboundgroup.Pinnable); ok {
		group.Pinned = pin.Pinned()
	}

	return group
}

// Исходы PatchSelector. Нулевое значение — «не получилось, запомненный выбор
// не трогать»: его же отдаёт мост, если вызов упал.
const (
	PatchFailed     = 0
	PatchDone       = 1
	PatchNoSelector = 2
	// Ядро держит конфиг другой подписки (или никакой): выбор не применён
	PatchNotLoaded = 3
	// Узел сейчас скрыт (только для мобильной сети вне неё): не выбран
	PatchHidden = 4
)

// PatchSelector применяет выбор, только если ядро держит конфиг подписки
// profile. restore — выбор, сохранённый раньше: скрытый сейчас узел ставится
// как есть (группа пойдёт через него, когда его покажут); выбор пользователя
// скрытый узел не берёт.
func PatchSelector(profile, selector, name string, restore bool) int {
	result := PatchNotLoaded

	config.WithProfile(profile, func() {
		result = patchSelector(selector, name, restore)
	})

	return result
}

func patchSelector(selector, name string, restore bool) int {
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

	// Пустое имя снимает закрепление url-test/fallback: группа снова выбирает сама
	if _, pinnable := g.(outboundgroup.Pinnable); pinnable && name == "" {
		s.ForceSet("")
	} else if err := s.Set(name); errors.Is(err, outboundgroup.ErrHidden) {
		if !restore {
			log.Infoln("Patch selector `%s`: %s is hidden", selector, name)

			return PatchHidden
		}

		s.ForceSet(name)
	} else if err != nil {
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

// splitSubtitle — имя сервера по ui-subtitle-pattern: совпадение уходит из
// заголовка в подпись. ok=false — шаблона нет или он не совпал
func splitSubtitle(name string, pattern *regexp2.Regexp) (title, subtitle string, ok bool) {
	if pattern == nil {
		return "", "", false
	}

	runes := []rune(name)
	match, err := pattern.FindRunesMatch(runes)
	if err != nil || match == nil {
		return "", "", false
	}

	return string(runes[:match.Index]) + string(runes[match.Index+match.Length:]),
		string(runes[match.Index : match.Index+match.Length]), true
}

// nodeDelays — список select: сам он членов не проверяет, и вложенная группа
// в нём без ручной проверки осталась бы без цифры
func convertProxies(proxies []C.Proxy, uiSubtitlePattern *regexp2.Regexp, groupTestURL string, nodeDelays bool) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range proxies {
		name := p.Name()
		title := name
		subtitle := p.Type().String()

		// Сервер — подпись с транспортом, как у панели; у групп и протоколов
		// без подписи — тип ядра.
		if server, ok := p.(interface{ Node() *adapter.Node }); ok {
			if node := server.Node(); node != nil {
				label := panel.Label(panel.KindOf(p.Type()), panel.Transport{
					Network: node.Network, TLS: node.TLS, RealityKey: node.RealityKey,
					TLSMirrorKey: node.TLSMirrorKey, HTTPUpgrade: node.HTTPUpgrade,
				})
				if label != "" {
					subtitle = label
				}
			}
		}

		if _, ok := p.Adapter().(outboundgroup.ProxyGroup); !ok {
			if cut, matched, ok := splitSubtitle(name, uiSubtitlePattern); ok {
				title, subtitle = cut, matched
			}
		}

		testURL := groupTestURL
		if testURL == "" {
			testURL = C.DefaultTestURL
		}

		delay := shownDelay(p, testURL)

		group, isGroup := p.Adapter().(outboundgroup.ProxyGroup)

		current, currentTitle, currentGroup := "", "", ""

		if isGroup {
			if node, owner := shownNode(group); node != nil {
				current, currentTitle, currentGroup = node.Name(), node.Name(), owner.Name()
				if cut, _, ok := splitSubtitle(current, uiSubtitlePattern); ok {
					currentTitle = cut
				}

				// Вложенная группа показывает задержку узла, на котором стоит
				// сейчас, — ту же цифру, что видна внутри неё
				if nodeDelays {
					delay = shownDelay(node, GroupTestURL(owner))
				}
			}
		}

		result = append(result, &Proxy{
			Name:         name,
			Title:        strings.TrimSpace(title),
			Subtitle:     strings.TrimSpace(subtitle),
			Type:         p.Type().String(),
			Delay:        delay,
			IsGroup:      isGroup,
			Current:      current,
			CurrentTitle: strings.TrimSpace(currentTitle),
			CurrentGroup: currentGroup,
		})
	}
	return result
}
