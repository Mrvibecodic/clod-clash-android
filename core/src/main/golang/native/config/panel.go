package config

import (
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/tunnel"

	"cfa/native/config/panel"
)

type (
	PanelInfo  = panel.Info
	PanelGroup = panel.Group
)

type ModeState struct {
	Mode   string           `json:"mode,omitempty"`
	Source panel.ModeSource `json:"source"`
}

func modeName(mode *tunnel.TunnelMode) *string {
	if mode == nil {
		return nil
	}

	name := mode.String()

	return &name
}

func QueryMode(profileDir, session string) ModeState {
	info := panel.ReadWithMode(profileDir, func() string {
		cfg, err := unmarshalProfile(profileDir)
		if err != nil {
			return ""
		}

		return cfg.Mode.String()
	})

	mode, source := panel.ResolveMode(
		info.Mode,
		modeName(overrideMode(ReadOverride(OverrideSlotPersist))),
		modeName(overrideMode(session)),
		modeLocked(info),
	)

	return ModeState{Mode: mode, Source: source}
}

func readPanelInfo(dir string) PanelInfo {
	return panel.Read(dir)
}

func writePanelInfo(dir string, info PanelInfo) {
	panel.Write(dir, info)
}

func applyHeaders(info *PanelInfo, header map[string][]string, current string) {
	panel.ApplyHeaders(info, header, current)
}

func applyGroups(info *PanelInfo, cfg *config.RawConfig, template tunnel.TunnelMode) {
	if cfg == nil {
		return
	}

	all := make([]string, 0, len(cfg.Proxy))
	for _, proxy := range cfg.Proxy {
		if name, ok := proxy["name"].(string); ok && name != "" {
			all = append(all, name)
		}
	}

	descriptions := make(map[string]string, len(cfg.Proxy))
	for _, proxy := range cfg.Proxy {
		name, ok := proxy["name"].(string)
		if !ok || name == "" {
			continue
		}

		for _, key := range []string{"serverDescription", "server_description", "server-description"} {
			if text := panel.Description(proxy[key]); text != "" {
				descriptions[name] = text

				break
			}
		}
	}

	if len(descriptions) == 0 {
		descriptions = nil
	}

	info.Descriptions = descriptions

	groups := make([]PanelGroup, 0, len(cfg.ProxyGroup))
	for _, raw := range cfg.ProxyGroup {
		name, _ := raw["name"].(string)
		if name == "" {
			continue
		}

		if panel.Hidden(raw["hidden"]) {
			continue
		}

		kind, _ := raw["type"].(string)

		var proxies []string
		if list, ok := raw["proxies"].([]any); ok {
			for _, item := range list {
				if value, ok := item.(string); ok && value != "" {
					proxies = append(proxies, value)
				}
			}
		}

		if len(proxies) == 0 {
			proxies = all
		}

		groups = append(groups, PanelGroup{Name: name, Type: kind, Proxies: proxies})
	}

	info.Groups = groups
	info.Main = panel.MainGroup(groups, cfg.Rule)
	info.Mode = template.String()
}
