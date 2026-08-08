package config

import (
	"encoding/base64"
	"encoding/json"
	"net/url"
	"os"
	P "path"
	"sort"
	"strconv"
	"strings"
	"time"

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
	LogoURL     string `json:"logoUrl,omitempty"`
	Announce    string `json:"announce,omitempty"`
	AnnounceURL string `json:"announceUrl,omitempty"`
	SupportURL  string `json:"supportUrl,omitempty"`
	HomeURL     string `json:"homeUrl,omitempty"`
	PortalURL   string `json:"portalUrl,omitempty"`
	Promo       string `json:"promo,omitempty"`
	PromoURL    string `json:"promoUrl,omitempty"`

	// Имя файла с логотипом рядом с `config.yaml`, если его удалось скачать.
	// Держим именно имя, а не путь: каталог профиля приложение и так знает,
	// а путь пережил бы переезд каталога только на бумаге.
	LogoFile string `json:"logoFile,omitempty"`

	// Текст провайдера для диалогов устройства (`clod-hwid-limit`).
	// Отдельный заголовок, а не `announce`: объявление на главной видят все,
	// а это объяснение адресовано одному заблокированному устройству.
	HwidLimitMessage string `json:"hwidLimitMessage,omitempty"`

	// Состояние устройства по ответу панели.
	//
	// `unknown` — про устройства ничего не пришло: панель их не считает
	// либо мы не отправили `x-hwid`. `active` — устройство зарегистрировано.
	// `limit` — лимит исчерпан, и ТЕЛО ОТВЕТА при этом заглушка: узлы там
	// с адресом 0.0.0.0, перезаписывать ими рабочую конфигурацию нельзя.
	// `not-supported` — панель ждёт идентификатор, которого мы не прислали.
	HwidState      string `json:"hwidState,omitempty"`
	HwidMaxDevices int    `json:"hwidMaxDevices,omitempty"`

	// Когда обновится трафик, в секундах Unix. Срок подписки и обновление
	// трафика — разные вещи: трафик может обновиться в середине оплаченного
	// месяца, и человеку важно знать когда.
	RefillDate int64 `json:"refillDate,omitempty"`

	// Пороги напоминаний: за сколько дней до конца подписки (`notify-expire-days`)
	// и на каком проценте израсходованного трафика (`notify-traffic-percent`).
	//
	// БЕЗ `omitempty` намеренно: `null` и `[]` здесь значат РАЗНОЕ. `null` —
	// панель про напоминания не сказала ничего, и клиент берёт свои умолчания.
	// Пустой список — панель напоминания выключила, и молчать надо совсем.
	// С `omitempty` оба случая записались бы одинаково.
	NotifyExpireDays     []int `json:"notifyExpireDays"`
	NotifyTrafficPercent []int `json:"notifyTrafficPercent"`

	// В конфигурации не осталось ни одного настоящего сервера: пришли одни
	// узлы-обманки. Это не ошибка загрузки, а состояние, о котором экрану
	// надо рассказать словами.
	NoServers bool `json:"noServers,omitempty"`

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
func applyHeaders(info *PanelInfo, header map[string][]string) {
	if header == nil {
		return
	}

	info.Title = firstNonEmpty(headerValue(header, "profile-title"), info.Title)
	info.LogoURL = firstNonEmpty(httpsURL(headerValue(header, "profile-logo")), info.LogoURL)
	info.Announce = firstNonEmpty(truncate(headerValue(header, "announce"), announceMaxChars), info.Announce)
	info.AnnounceURL = firstNonEmpty(httpsURL(headerValue(header, "announce-url")), info.AnnounceURL)
	info.SupportURL = firstNonEmpty(contactURL(headerValue(header, "support-url")), info.SupportURL)
	info.HomeURL = firstNonEmpty(httpsURL(headerValue(header, "profile-web-page-url")), info.HomeURL)
	info.PortalURL = firstNonEmpty(httpsURL(headerValue(header, "clod-portal-url")), info.PortalURL)
	info.Promo = firstNonEmpty(truncate(headerValue(header, "clod-promo"), announceMaxChars), info.Promo)
	info.PromoURL = firstNonEmpty(httpsURL(headerValue(header, "clod-promo-url")), info.PromoURL)
	info.HwidLimitMessage = firstNonEmpty(
		truncate(headerValue(header, "clod-hwid-limit"), announceMaxChars),
		info.HwidLimitMessage,
	)

	// Оба поля перезаписываются безусловно, а не «если пришло»: это состояние
	// последнего ответа, а не накопленное знание. Панель перестала слать
	// число устройств — значит его больше нет, а не «оставим прошлое».
	info.HwidState = hwidState(header)
	info.HwidMaxDevices, _ = parseUint(headerValue(header, "x-hwid-max-devices"))
	info.RefillDate = parseRefillDate(headerValue(header, "subscription-refill-date"))

	// Тоже безусловно: пороги — состояние последнего ответа. Панель перестала
	// их слать — значит вернулись умолчания, а не «оставим прошлые».
	info.NotifyExpireDays = thresholds(headerValue(header, "notify-expire-days"), 1, 365)
	if info.NotifyExpireDays == nil && boolHeader(header, "notification-subs-expire") {
		// Совместимость с Happ: голый тумблер без списка включает умолчания.
		info.NotifyExpireDays = append([]int(nil), defaultNotifyExpireDays...)
	}

	info.NotifyTrafficPercent = thresholds(headerValue(header, "notify-traffic-percent"), 1, 100)
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
//
// Ключи перебираются по алфавиту, а не в порядке обхода map: если панель
// прислала и `announce`, и `x-amz-meta-announce`, победитель должен быть один
// и тот же от запуска к запуску, иначе баннер меняется сам по себе.
func headerValue(header map[string][]string, name string) string {
	keys := make([]string, 0, len(header))
	for key := range header {
		keys = append(keys, key)
	}

	sort.Strings(keys)

	for _, key := range keys {
		lower := strings.ToLower(key)
		if lower != name && !strings.HasSuffix(lower, "-"+name) {
			continue
		}

		for _, value := range header[key] {
			if decoded := decodeHeaderValue(value); decoded != "" {
				return decoded
			}
		}
	}

	return ""
}

// httpsURL пропускает только `https://` с непустым хостом.
//
// Значение уходит прямо в `Intent(ACTION_VIEW)`, то есть открывается одним
// нажатием из содержимого, которым панель распоряжается целиком. `http://` —
// это и понижение, и признак кривой настройки; `javascript:`, `file:`,
// `intent:` и прочее не должны доезжать до системы вообще.
func httpsURL(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}

	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" {
		return ""
	}

	return value
}

// contactURL — то же, плюс схемы, которыми законно пользуется поддержка.
func contactURL(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}

	parsed, err := url.Parse(value)
	if err != nil {
		return ""
	}

	switch parsed.Scheme {
	case "https":
		if parsed.Host == "" {
			return ""
		}
	case "tg", "mailto":
	default:
		return ""
	}

	return value
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

	// Значение объявило себя base64 и им не оказалось: считаем, что заголовка
	// не было. Литерал `base64:…` в баннере хуже пустого места.
	return ""
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

// Состояния устройства, как их видит экран.
const (
	HwidUnknown      = ""
	HwidActive       = "active"
	HwidLimitReached = "limit"
	HwidNotSupported = "not-supported"
)

// hwidState разбирает ответные заголовки семейства `x-hwid-*`.
//
// Порядок проверок важен. `x-hwid-not-supported` идёт первым намеренно:
// Remnawave 3.x в ветке блокировки по устройствам ставит `x-hwid-limit: true`
// ВСЕГДА, а `x-hwid-max-devices-reached` — только при настоящем превышении.
// Пара «limit без max-devices-reached» означает «панель ждёт идентификатор,
// которого не получила», а не «лимит исчерпан».
func hwidState(header map[string][]string) string {
	if boolHeader(header, "x-hwid-not-supported") {
		return HwidNotSupported
	}

	if boolHeader(header, "x-hwid-max-devices-reached") || boolHeader(header, "x-hwid-limit") {
		return HwidLimitReached
	}

	if boolHeader(header, "x-hwid-active") {
		return HwidActive
	}

	return HwidUnknown
}

// Умолчания напоминаний, если панель прислала только тумблер `notification-subs-expire`.
var defaultNotifyExpireDays = []int{1, 3, 7}

// Сколько порогов панели позволено задать. Ограничение от чужой ошибки:
// список на тысячу значений — это тысяча уведомлений, а не забота.
const maxThresholds = 10

// thresholds разбирает список порогов вида `7,3,1`.
//
// Возвращает nil, если панель ничего внятного не сказала (клиент возьмёт свои
// умолчания), и ПУСТОЙ список на `off`/`false` — это осознанное «напоминаний
// не надо», и его нельзя путать с молчанием.
func thresholds(raw string, lo, hi int) []int {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil
	}

	if strings.EqualFold(trimmed, "off") || strings.EqualFold(trimmed, "false") {
		return []int{}
	}

	seen := make(map[int]bool)
	values := make([]int, 0, maxThresholds)

	for _, part := range strings.Split(trimmed, ",") {
		value, err := strconv.Atoi(strings.TrimSpace(part))
		if err != nil || value < lo || value > hi || seen[value] {
			continue
		}

		seen[value] = true
		values = append(values, value)
	}

	if len(values) == 0 {
		return nil
	}

	sort.Ints(values)

	if len(values) > maxThresholds {
		values = values[:maxThresholds]
	}

	return values
}

func boolHeader(header map[string][]string, name string) bool {
	switch strings.ToLower(strings.TrimSpace(headerValue(header, name))) {
	case "true", "1", "yes", "on":
		return true
	}

	return false
}

func parseUint(raw string) (int, bool) {
	value, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil || value < 0 {
		return 0, false
	}

	return value, true
}

// parseRefillDate разбирает дату обновления трафика.
//
// Формат сервисы шлют разный: и секунды Unix, и миллисекунды, и обычную дату.
// Разбираем всё, что узнаём, остальное молча игнорируем — неверная дата хуже,
// чем её отсутствие.
func parseRefillDate(raw string) int64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}

	if value, err := strconv.ParseInt(raw, 10, 64); err == nil {
		// Миллисекунды: всё, что больше «года 33658-го» в секундах.
		if value > 1e12 {
			value /= 1000
		}

		if value > 0 {
			return value
		}

		return 0
	}

	for _, layout := range []string{
		time.RFC3339,
		"2006-01-02T15:04:05",
		"2006-01-02 15:04:05",
		"2006-01-02",
	} {
		if parsed, err := time.Parse(layout, raw); err == nil {
			return parsed.Unix()
		}
	}

	return 0
}
