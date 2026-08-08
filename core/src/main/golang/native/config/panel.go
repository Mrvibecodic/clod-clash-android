package config

import (
	"github.com/metacubex/mihomo/config"

	"cfa/native/config/panel"
)

// Разбор заголовков панели вынесен в отдельный пакет `panel`: он не зависит
// ни от ядра, ни от Android, и поэтому проверяется обычными Go-тестами на
// любой машине. Здесь остаётся только то, чему нужен разобранный конфиг,
// и имена, которыми пользуется остальной пакет.
type (
	PanelInfo  = panel.Info
	PanelGroup = panel.Group
)

// Состояния устройства, как их видит экран.
const (
	HwidUnknown      = panel.HwidUnknown
	HwidActive       = panel.HwidActive
	HwidLimitReached = panel.HwidLimitReached
	HwidNotSupported = panel.HwidNotSupported
)

func readPanelInfo(dir string) PanelInfo {
	return panel.Read(dir)
}

func writePanelInfo(dir string, info PanelInfo) {
	panel.Write(dir, info)
}

func applyHeaders(info *PanelInfo, header map[string][]string, current string) {
	panel.ApplyHeaders(info, header, current)
}

// applyGroups достаёт из разобранного конфига состав групп.
func applyGroups(info *PanelInfo, cfg *config.RawConfig) {
	if cfg == nil {
		return
	}

	// Полный список узлов — им подменяется состав группы, которая набирается
	// не перечислением, а из proxy-provider'а: имён оттуда в конфиге нет.
	all := make([]string, 0, len(cfg.Proxy))
	for _, proxy := range cfg.Proxy {
		if name, ok := proxy["name"].(string); ok && name != "" {
			all = append(all, name)
		}
	}

	groups := make([]PanelGroup, 0, len(cfg.ProxyGroup))
	for _, raw := range cfg.ProxyGroup {
		name, _ := raw["name"].(string)
		if name == "" {
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
}
