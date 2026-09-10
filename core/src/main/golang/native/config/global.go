package config

import (
	"cfa/native/config/groups"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

const globalResolveDepth = 16

func globalGroupDeclared(rawCfg *config.RawConfig) bool {
	if rawCfg == nil {
		return false
	}

	for _, group := range rawCfg.ProxyGroup {
		if name, ok := group["name"].(string); ok && name == groups.GlobalGroupName {
			return true
		}
	}

	return false
}

func resolveTerminal(p C.Proxy) string {
	for depth := 0; depth < globalResolveDepth; depth++ {
		g, isGroup := p.Adapter().(outboundgroup.ProxyGroup)
		if !isGroup {
			return p.Name()
		}

		now := g.Now()
		if now == "" {
			return p.Name()
		}

		var next C.Proxy

		for _, px := range g.Proxies() {
			if px.Name() == now {
				next = px

				break
			}
		}

		if next == nil {
			return now
		}

		p = next
	}

	return p.Name()
}

func pinGlobalDefault() {
	if tunnel.Mode() != tunnel.Global {
		return
	}

	p := tunnel.Proxies()[groups.GlobalGroupName]
	if p == nil {
		return
	}

	g, isGroup := p.Adapter().(outboundgroup.ProxyGroup)
	if !isGroup {
		return
	}

	s, selectable := g.(outboundgroup.SelectAble)
	if !selectable {
		return
	}

	proxies := g.Proxies()

	members := make([]groups.GlobalMember, 0, len(proxies))

	for _, px := range proxies {
		members = append(members, groups.GlobalMember{Name: px.Name(), Resolved: resolveTerminal(px)})
	}

	if groups.GlobalSelectionRoutable(members, g.Now()) {
		return
	}

	name := groups.PreferredGlobalSelection(members)

	if err := s.Set(name); err != nil {
		log.Errorln("[APP] GLOBAL has no routable selection and %s cannot be pinned: %s", name, err.Error())

		return
	}

	log.Infoln("[APP] GLOBAL had no routable selection, pinned %s", name)
}
