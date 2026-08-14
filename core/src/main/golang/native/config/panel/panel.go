package panel

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	P "path"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Info — то, что известно о подписке помимо самого конфига.
//
// Лежит файлом `panel.json` рядом с `config.yaml` в каталоге профиля, а не
// в базе: заводить колонки в Room ради каждого нового заголовка панели —
// это миграция на каждый чих, а данные всё равно живут ровно столько же,
// сколько сам профиль, и обновляются вместе с ним.
type Info struct {
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

	// Остальные ссылки провайдера — те же, что на ПК.
	//
	// Все ссылки (включая `SupportURL`, `HomeURL` и `PortalURL` выше) живут
	// ровно один ответ панели: пришли — есть, не пришли — нет. Накапливать их
	// нельзя, иначе кабинет прежнего провайдера остался бы на профиле, куда
	// вставили чужую ссылку.
	//
	// `clod-bot-url` — бот. Отдельно от поддержки намеренно: бот выдаёт
	// ссылку, продлевает и отвечает сам, а поддержка — живой человек, и
	// отправлять к нему тех, кому хватило бы бота, незачем. Схемы у бота те
	// же, что у поддержки: адрес почти всегда `tg:`.
	//
	// `clod-monitor-url` — страница состояния серверов, `clod-guide-url` —
	// инструкция провайдера. Это обычные страницы, поэтому только `https`.
	BotURL     string `json:"botUrl,omitempty"`
	MonitorURL string `json:"monitorUrl,omitempty"`
	GuideURL   string `json:"guideUrl,omitempty"`

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

	// Насколько часы панели опережают часы устройства, в секундах, и когда
	// это измерено (по часам устройства). Заголовок `Date` есть у любого
	// сервера с часами, поэтому мера бесплатная — ни запроса, ни договорённости
	// с провайдером. Нужна затем, что срок подписки считается по часам
	// устройства, а они на телефоне бывают сбиты на часы и дни.
	ClockSkew   int64 `json:"clockSkew,omitempty"`
	ClockSkewAt int64 `json:"clockSkewAt,omitempty"`

	// Куда провайдер просит переехать (`new-url` или `new-domain`), уже
	// проверенный адрес. Пусто — переезда не просили. Сам переезд делает
	// приложение и только после пробной загрузки: опечатка в панели иначе
	// оставила бы человека на мёртвом адресе.
	MigrateURL string `json:"migrateUrl,omitempty"`

	// `clod-lock-mode` — провайдер запрещает менять режим туннеля.
	// Указатель ради третьего состояния: панель может ничего не сказать,
	// и это не то же самое, что «разрешаю».
	LockMode *bool `json:"lockMode,omitempty"`

	// В конфигурации не осталось ни одного настоящего сервера: пришли одни
	// узлы-обманки. Это не ошибка загрузки, а состояние, о котором экрану
	// надо рассказать словами.
	NoServers bool `json:"noServers,omitempty"`

	// Запасные адреса подписки на случай, когда основной не отвечает.
	//
	// `fallback-url` — адрес целиком, `fallback-domain` — только хост, который
	// подставляется в основной адрес. Отличие от переезда (`new-url`): сюда
	// ходят ТОЛЬКО когда основной адрес не ответил, и сохранённый адрес
	// подписки при этом не меняется — в следующий раз снова пробуется он.
	// Провайдер обычно даёт запасной домен на случай блокировки основного,
	// и подменять им рабочий адрес навсегда нельзя: заблокируют и его.
	FallbackURL    string `json:"fallbackUrl,omitempty"`
	FallbackDomain string `json:"fallbackDomain,omitempty"`

	// Описания узлов из конфигурации (`serverDescription` у узла) по именам.
	//
	// Через API ядра они не приходят: mihomo о таком поле не знает и молча
	// проносит его мимо. Поэтому собираем их при разборе конфигурации и кладём
	// сюда — рядом с составом групп, который нужен по той же причине.
	Descriptions map[string]string `json:"descriptions,omitempty"`

	// `clod-show-0hosts` — провайдер просит НЕ прятать узлы-обманки.
	//
	// Наши экраны «серверов нет» — поведение по умолчанию, и оно остаётся.
	// Заголовок их отключает: узлы-заглушки показываются как есть, со своими
	// названиями, фильтр не работает. Нужен тем, кто складывает в эти узлы
	// собственный текст для человека и хочет, чтобы человек его прочитал.
	ShowZeroHosts bool `json:"showZeroHosts,omitempty"`

	// Состав конфига: нужен, чтобы показать список серверов ДО подключения.
	// Пока туннель не поднят, ядро ничего не знает о группах и узлах —
	// спрашивать у него нечего, а список человек хочет видеть сразу.
	Groups []Group `json:"groups,omitempty"`
}

type Group struct {
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

// Read возвращает уже сохранённые данные или пустую структуру.
// Отсутствие файла и битый JSON — не ошибка: панель могла и не прислать ничего.
func Read(dir string) Info {
	var info Info

	bytes, err := os.ReadFile(panelPath(dir))
	if err != nil {
		return info
	}

	_ = json.Unmarshal(bytes, &info)

	return info
}

func Write(dir string, info Info) {
	bytes, err := json.Marshal(&info)
	if err != nil {
		return
	}

	_ = os.WriteFile(panelPath(dir), bytes, 0o644)
}

// ApplyHeaders складывает в Info то, что панель прислала заголовками.
//
// Поиск по суффиксу и без учёта регистра: панели ставят одни и те же поля
// то как `announce`, то как `x-announce`. Значение может прийти как
// `base64:<payload>` — так панели передают кириллицу, которую нельзя положить
// в заголовок сырыми байтами.
func ApplyHeaders(info *Info, header map[string][]string, current string) {
	if header == nil {
		return
	}

	// ВСЁ, что панель рассказывает о себе, — СОСТОЯНИЕ ПОСЛЕДНЕГО ОТВЕТА, а не
	// накопленное знание: убрала заголовок — значение пропало тем же
	// обновлением. Копить их нельзя. Сохранённая ссылка пережила бы и смену
	// тарифа, и подмену ссылки подписки на ДРУГОГО провайдера, и человек
	// уходил бы в чужой кабинет; снятое объявление висело бы вечно, а
	// логотип прежнего провайдера — поверх нового. На ПК то же самое
	// (`merge_panel_meta`: «replace, do not merge»).
	//
	// Исключение ровно одно — название подписки ниже.
	//
	// Боту достаётся проверка поддержки (`tg:` и `mailto:` законны),
	// мониторингу и инструкции — обычная: это страницы, и ничего, кроме
	// https, за ними быть не должно.
	info.LogoURL = httpsURL(headerValue(header, "profile-logo"))
	info.Announce = truncate(headerValue(header, "announce"), announceMaxChars)
	info.AnnounceURL = httpsURL(headerValue(header, "announce-url"))
	info.SupportURL = contactURL(headerValue(header, "support-url"))
	info.HomeURL = httpsURL(headerValue(header, "profile-web-page-url"))
	info.PortalURL = httpsURL(headerValue(header, "clod-portal-url"))
	info.BotURL = contactURL(headerValue(header, "clod-bot-url"))
	info.MonitorURL = httpsURL(headerValue(header, "clod-monitor-url"))
	info.GuideURL = httpsURL(headerValue(header, "clod-guide-url"))
	info.Promo = truncate(headerValue(header, "clod-promo"), announceMaxChars)
	info.PromoURL = httpsURL(headerValue(header, "clod-promo-url"))
	info.HwidLimitMessage = truncate(headerValue(header, "clod-hwid-limit"), announceMaxChars)

	// А название держится до следующего непустого — и это не поблажка панели,
	// а защита человека: под этим именем подписка лежит в списке, и остаться
	// без имени из-за одного ответа без заголовка она не должна.
	info.Title = firstNonEmpty(headerValue(header, "profile-title"), info.Title)

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

	// `Date` берём ПРЯМЫМ ключом: это стандартный заголовок, и ни поиск
	// по суффиксу, ни разбор base64, которыми живут заголовки панели,
	// к нему не применимы. Ответ без `Date` ничего не говорит о часах
	// устройства, поэтому прошлое измерение остаётся как было.
	if served := serverTime(header); served > 0 {
		now := time.Now().Unix()

		info.ClockSkew = served - now
		info.ClockSkewAt = now
	}

	// Переезд подписки: `new-url` — адрес целиком, `new-domain` — только хост
	// (можно с портом) в текущем адресе. Первый приоритетнее.
	info.MigrateURL = firstNonEmpty(
		validateNewURL(current, headerValue(header, "new-url")),
		swapDomain(current, headerValue(header, "new-domain")),
	)

	// Мусор в значении режим НЕ включает (`boolHeader`, а не `optionalBool`):
	// молча отдать человеку чужой текст вместо объяснения хуже, чем
	// проигнорировать кривую панель. Как на ПК.
	// Запасные адреса — тоже состояние последнего ответа: убрал провайдер
	// заголовок, значит запасного адреса больше нет.
	info.FallbackURL = httpsURL(headerValue(header, "fallback-url"))
	info.FallbackDomain = strings.TrimSpace(headerValue(header, "fallback-domain"))

	info.ShowZeroHosts = boolHeader(header, "clod-show-0hosts")

	info.LockMode = optionalBool(header, "clod-lock-mode")

	// `global-mode: false` у панелей, настроенных под Prizrak-Box, значит
	// «спрячьте переключатель режимов» — то же самое, что наш замок.
	if info.LockMode == nil {
		if allowed := optionalBool(header, "global-mode"); allowed != nil {
			locked := !*allowed
			info.LockMode = &locked
		}
	}
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

// serverTime — часы сервера из заголовка `Date` в секундах Unix, 0 — нет.
func serverTime(header map[string][]string) int64 {
	for key, values := range header {
		if !strings.EqualFold(key, "date") {
			continue
		}

		for _, value := range values {
			if parsed, err := http.ParseTime(strings.TrimSpace(value)); err == nil {
				return parsed.Unix()
			}
		}
	}

	return 0
}

// validateNewURL проверяет адрес, на который панель просит переехать.
//
// Понижение https → http запрещено: заголовком ответа могла бы увести
// загрузку подписки на открытый канал та же сторона, которую мы и проверяем.
// Совпадающий с текущим адрес переездом не считается.
func validateNewURL(current, candidate string) string {
	candidate = strings.TrimSpace(candidate)
	if candidate == "" {
		return ""
	}

	parsed, err := url.Parse(candidate)
	if err != nil || parsed.Host == "" {
		return ""
	}

	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return ""
	}

	if now, err := url.Parse(current); err == nil && now.Scheme == "https" && parsed.Scheme != "https" {
		return ""
	}

	if parsed.String() == current {
		return ""
	}

	return parsed.String()
}

// swapDomain меняет в текущем адресе хост (и порт, если он задан).
//
// Принимает `example.com`, `example.com:8443` и `https://example.com` — панели
// пишут по-разному, а смысл один.
func swapDomain(current, domain string) string {
	domain = strings.TrimRight(strings.TrimSpace(domain), "/")
	if domain == "" || current == "" {
		return ""
	}

	// Схему из значения выбрасываем: меняем хост в текущем адресе, а не адрес
	// целиком — для адреса целиком есть `new-url`.
	if _, rest, ok := strings.Cut(domain, "://"); ok {
		domain = rest
	}

	domain, _, _ = strings.Cut(domain, "/")
	if domain == "" {
		return ""
	}

	// Порт разрешён, но только числом в допустимом диапазоне: «example.com:abc»
	// и «example.com:99999» это не адреса, а полная пробная загрузка по такому
	// кандидату стоит столько же, сколько по настоящему.
	//
	// Режем по ПОСЛЕДНЕМУ двоеточию: у адреса IPv6 их много, и первый же
	// разрез превратил бы `[2001:db8::1]:8443` в мусор.
	if at := strings.LastIndex(domain, ":"); at >= 0 && !strings.HasSuffix(domain, "]") {
		host, port := domain[:at], domain[at+1:]

		if host == "" || port == "" {
			return ""
		}

		number, err := strconv.Atoi(port)
		if err != nil || number < 1 || number > 65535 || strings.ContainsAny(port, "+-") {
			return ""
		}
	}

	parsed, err := url.Parse(current)
	if err != nil || parsed.Host == "" {
		return ""
	}

	parsed.Host = domain

	if parsed.String() == current {
		return ""
	}

	return parsed.String()
}

// optionalBool отличает «панель сказала false» от «панель промолчала».
//
// Обычный boolHeader на оба случая отвечает false, а для замка режимов это
// разные вещи: молчание оставляет решение человеку, явный false — снимает
// замок, который мог стоять раньше.
func optionalBool(header map[string][]string, name string) *bool {
	switch strings.ToLower(strings.TrimSpace(headerValue(header, name))) {
	case "true", "1", "yes", "on":
		value := true

		return &value
	case "false", "0", "no", "off":
		value := false

		return &value
	}

	return nil
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

// Ограничение длины описания узла. Панель Remnawave режет своё поле до
// тридцати символов, но конфиг может прийти и не от неё, а строка эта живёт
// подписью в строке списка — простыня там всё равно не поместится.
const descriptionMaxChars = 60

// Description приводит значение поля `serverDescription` к тому, что можно
// показать. Не строка, пустая строка и пробелы дают пустой результат.
func Description(raw any) string {
	value, ok := raw.(string)
	if !ok {
		return ""
	}

	return truncate(strings.TrimSpace(value), descriptionMaxChars)
}

// SpareAddresses — куда идти, если основной адрес подписки не ответил.
//
// Порядок тот, которого ждёт панель: сначала целиком запасной адрес
// (`fallback-url`), потом основной адрес с подменённым хостом
// (`fallback-domain`). Совпадения с основным адресом отбрасываются: повторять
// то, что уже не сработало, незачем.
func (i Info) SpareAddresses(current string) []string {
	spares := make([]string, 0, 2)

	for _, candidate := range []string{
		httpsURL(i.FallbackURL),
		swapDomain(current, i.FallbackDomain),
	} {
		if candidate == "" || candidate == current {
			continue
		}

		duplicate := false
		for _, known := range spares {
			if known == candidate {
				duplicate = true

				break
			}
		}

		if !duplicate {
			spares = append(spares, candidate)
		}
	}

	return spares
}
