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

	// Описания узлов: панель кладёт их прямо в узел, ядро о таком поле
	// не знает и через API его не отдаёт — собираем при разборе конфигурации.
	// Ключ пишут по-разному: Remnawave шлёт `serverDescription`, клиенты-доноры
	// принимают ещё две записи, а конфиг может прийти и не от панели.
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

		// `hidden: true` — просьба конфига не показывать группу в интерфейсе.
		// Ядро её уважает (см. `QueryProxyGroupNames`: там отсеиваются группы
		// с `Hidden()`), а этот разбор — нет, и на вкладке «Серверы» до
		// подключения вылезали служебные группы шаблона: балансировщик,
		// спрятанный `PROXY`. После подключения они пропадали — то есть список
		// групп менялся сам собой.
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
}
