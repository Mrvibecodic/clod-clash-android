package config

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"os"
	P "path"
	"strings"

	"github.com/metacubex/mihomo/config"
)

// PanelInfo — то, что известно о подписке помимо самого конфига.
//
// Лежит файлом `panel.json` рядом с `config.yaml` в каталоге профиля, а не
// в базе: заводить колонки в Room ради каждого нового заголовка панели —
// это миграция на каждый чих, а данные всё равно живут ровно столько же,
// сколько сам профиль, и обновляются вместе с ним.
type PanelInfo struct {
	// Заголовки ответа панели.
	Title       string `json:"title,omitempty"`
	Announce    string `json:"announce,omitempty"`
	AnnounceURL string `json:"announceUrl,omitempty"`
	SupportURL  string `json:"supportUrl,omitempty"`
	HomeURL     string `json:"homeUrl,omitempty"`
	RenewURL    string `json:"renewUrl,omitempty"`
	TopupURL    string `json:"topupUrl,omitempty"`
	Promo       string `json:"promo,omitempty"`
	PromoURL    string `json:"promoUrl,omitempty"`

	// Состав конфига: нужен, чтобы показать список серверов ДО подключения.
	// Пока туннель не поднят, ядро ничего не знает о группах и узлах —
	// спрашивать у него нечего, а список человек хочет видеть сразу.
	Groups []PanelGroup `json:"groups,omitempty"`
}

type PanelGroup struct {
	Name    string   `json:"name"`
	Type    string   `json:"type"`
	Proxies []string `json:"proxies,omitempty"`
}

const panelFileName = "panel.json"

// Ограничение длины баннеров — как на десктопе: панель может прислать простыню,
// а место на карточке конечное.
const announceMaxChars = 300

func panelPath(dir string) string {
	return P.Join(dir, panelFileName)
}

// readPanelInfo возвращает уже сохранённые данные или пустую структуру.
// Отсутствие файла и битый JSON — не ошибка: панель могла и не прислать ничего.
func readPanelInfo(dir string) PanelInfo {
	var info PanelInfo

	bytes, err := os.ReadFile(panelPath(dir))
	if err != nil {
		return info
	}

	_ = json.Unmarshal(bytes, &info)

	return info
}

func writePanelInfo(dir string, info PanelInfo) {
	bytes, err := json.Marshal(&info)
	if err != nil {
		return
	}

	_ = os.WriteFile(panelPath(dir), bytes, 0o644)
}

// applyHeaders складывает в PanelInfo то, что панель прислала заголовками.
//
// Поиск по суффиксу и без учёта регистра: панели ставят одни и те же поля
// то как `announce`, то как `x-announce`. Значение может прийти как
// `base64:<payload>` — так панели передают кириллицу, которую нельзя положить
// в заголовок сырыми байтами.
func applyHeaders(info *PanelInfo, header http.Header) {
	if header == nil {
		return
	}

	info.Title = firstNonEmpty(headerValue(header, "profile-title"), info.Title)
	info.Announce = firstNonEmpty(truncate(headerValue(header, "announce"), announceMaxChars), info.Announce)
	info.AnnounceURL = firstNonEmpty(headerValue(header, "announce-url"), info.AnnounceURL)
	info.SupportURL = firstNonEmpty(headerValue(header, "support-url"), info.SupportURL)
	info.HomeURL = firstNonEmpty(headerValue(header, "profile-web-page-url"), info.HomeURL)
	info.RenewURL = firstNonEmpty(headerValue(header, "clod-renew-url"), info.RenewURL)
	info.TopupURL = firstNonEmpty(headerValue(header, "clod-topup-url"), info.TopupURL)
	info.Promo = firstNonEmpty(truncate(headerValue(header, "clod-promo"), announceMaxChars), info.Promo)
	info.PromoURL = firstNonEmpty(headerValue(header, "clod-promo-url"), info.PromoURL)
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

// headerValue ищет заголовок по суффиксу имени и разбирает `base64:`.
func headerValue(header http.Header, name string) string {
	for key, values := range header {
		lower := strings.ToLower(key)
		if lower != name && !strings.HasSuffix(lower, "-"+name) {
			continue
		}

		for _, value := range values {
			if decoded := decodeHeaderValue(value); decoded != "" {
				return decoded
			}
		}
	}

	return ""
}

func decodeHeaderValue(raw string) string {
	value := strings.TrimSpace(raw)

	payload, ok := strings.CutPrefix(value, "base64:")
	if !ok {
		return value
	}

	payload = strings.TrimSpace(payload)

	// Панели кодируют то стандартным алфавитом, то url-safe, и не всегда
	// добавляют выравнивание. Пробуем все четыре сочетания, прежде чем сдаться.
	for _, encoding := range []*base64.Encoding{
		base64.StdEncoding,
		base64.RawStdEncoding,
		base64.URLEncoding,
		base64.RawURLEncoding,
	} {
		if decoded, err := encoding.DecodeString(payload); err == nil {
			return strings.TrimSpace(string(decoded))
		}
	}

	return value
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}

	return ""
}

func truncate(value string, max int) string {
	runes := []rune(value)
	if len(runes) <= max {
		return value
	}

	return strings.TrimSpace(string(runes[:max])) + "…"
}
