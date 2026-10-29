package config

import (
	"time"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/tunnel"

	"cfa/native/config/panel"
)

type ModeState struct {
	Mode      string           `json:"mode,omitempty"`
	Source    panel.ModeSource `json:"source"`
	LockUntil int64            `json:"lockUntil,omitempty"`
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
		modeLocked(profileDir, info),
	)

	state := ModeState{Mode: mode, Source: source}
	if source == panel.ModeLocked {
		state.LockUntil = panel.LockUntil(panel.WithUpdatedAt(profileDir, info))
	}

	return state
}

func MarkUpdated(profileDir string, intervalMillis int64) {
	panel.MarkUpdated(profileDir, time.Now().Unix(), intervalMillis/1000)
}

func applyGroups(info *panel.Info, cfg *config.RawConfig, template tunnel.TunnelMode) {
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

	info.Protocols = panel.Protocols(cfg.Proxy)

	groups := panel.GroupsOf(cfg.ProxyGroup, all)

	info.Groups = groups
	info.Main = panel.MainGroup(groups, cfg.Rule)

	names := make([]string, 0, len(groups))
	for _, raw := range cfg.ProxyGroup {
		if name, _ := raw["name"].(string); name != "" {
			names = append(names, name)
		}
	}
	info.MobileOnly = panel.MobileOnlyOf(info.MobileOnly, cfg.Proxy, names)
	info.Mode = template.String()
}
