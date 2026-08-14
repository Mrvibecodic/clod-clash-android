package panel

import (
	"encoding/base64"
	"net/http"
	"reflect"
	"strings"
	"testing"
	"time"
)

// Проверки на разбор заголовков панели. Всё здесь — чистые функции: ни сети,
// ни ядра, ни устройства, поэтому гоняются на любой машине за миллисекунды.
//
// Смысл именно в этом наборе: заголовки приходят от чужой стороны, форматы
// у панелей разные, и почти каждая правка в этом файле раньше ловилась
// только глазами на телефоне.

func TestThresholdsSilenceIsNotOff(t *testing.T) {
	// Молчание панели и выключенные напоминания — РАЗНОЕ. nil значит
	// «клиент берёт свои умолчания», пустой список — «не напоминать вовсе».
	if got := thresholds("", 1, 365); got != nil {
		t.Fatalf("пустое значение должно давать nil, получено %#v", got)
	}

	if got := thresholds("   ", 1, 365); got != nil {
		t.Fatalf("пробелы должны давать nil, получено %#v", got)
	}

	for _, raw := range []string{"off", "OFF", "false", "False"} {
		got := thresholds(raw, 1, 365)
		if got == nil {
			t.Fatalf("%q должно давать пустой список, а не nil", raw)
		}

		if len(got) != 0 {
			t.Fatalf("%q должно давать пустой список, получено %#v", raw, got)
		}
	}
}

func TestThresholdsParsing(t *testing.T) {
	cases := []struct {
		name   string
		raw    string
		lo, hi int
		want   []int
	}{
		{name: "порядок не важен", raw: "7,3,1", lo: 1, hi: 365, want: []int{1, 3, 7}},
		{name: "пробелы вокруг чисел", raw: " 7 , 3 ", lo: 1, hi: 365, want: []int{3, 7}},
		{name: "повторы схлопываются", raw: "3,3,3,1", lo: 1, hi: 365, want: []int{1, 3}},
		{name: "мусор пропускается", raw: "3,абв,1", lo: 1, hi: 365, want: []int{1, 3}},
		{name: "вне диапазона пропускается", raw: "0,3,400", lo: 1, hi: 365, want: []int{3}},
		{name: "проценты", raw: "100,90,80", lo: 1, hi: 100, want: []int{80, 90, 100}},
		{name: "процент больше сотни пропускается", raw: "80,101", lo: 1, hi: 100, want: []int{80}},
		{name: "ничего годного — nil", raw: "абв,-5", lo: 1, hi: 365, want: nil},
	}

	for _, item := range cases {
		t.Run(item.name, func(t *testing.T) {
			got := thresholds(item.raw, item.lo, item.hi)
			if !reflect.DeepEqual(got, item.want) {
				t.Fatalf("thresholds(%q) = %#v, ожидалось %#v", item.raw, got, item.want)
			}
		})
	}
}

func TestThresholdsLimit(t *testing.T) {
	// Список на тысячу значений — это тысяча уведомлений, а не забота.
	raw := "1,2,3,4,5,6,7,8,9,10,11,12,13"

	got := thresholds(raw, 1, 365)
	if len(got) != maxThresholds {
		t.Fatalf("ожидалось не больше %d порогов, получено %d (%#v)", maxThresholds, len(got), got)
	}

	if got[0] != 1 || got[len(got)-1] != 10 {
		t.Fatalf("после обрезки должны остаться меньшие пороги, получено %#v", got)
	}
}

func TestServerTime(t *testing.T) {
	want := time.Date(2026, time.August, 8, 12, 0, 0, 0, time.UTC).Unix()

	// Ключ ищется без учёта регистра: заголовок приходит и как `Date`, и как `date`.
	for _, key := range []string{"Date", "date", "DATE"} {
		header := map[string][]string{key: {"Sat, 08 Aug 2026 12:00:00 GMT"}}

		if got := serverTime(header); got != want {
			t.Fatalf("serverTime(%q) = %d, ожидалось %d", key, got, want)
		}
	}

	if got := serverTime(map[string][]string{}); got != 0 {
		t.Fatalf("без заголовка ожидался 0, получено %d", got)
	}

	if got := serverTime(map[string][]string{"Date": {"вчера"}}); got != 0 {
		t.Fatalf("на неразбираемую дату ожидался 0, получено %d", got)
	}
}

func TestParseRefillDate(t *testing.T) {
	day := time.Date(2026, time.August, 8, 0, 0, 0, 0, time.UTC).Unix()
	moment := time.Date(2026, time.August, 8, 12, 30, 0, 0, time.UTC).Unix()

	cases := []struct {
		name string
		raw  string
		want int64
	}{
		{name: "пусто", raw: "", want: 0},
		{name: "секунды", raw: "1786309200", want: 1786309200},
		// Панели встречаются и с миллисекундами: без деления срок уезжал
		// в 50-тысячный год.
		{name: "миллисекунды", raw: "1786309200000", want: 1786309200},
		{name: "ноль", raw: "0", want: 0},
		{name: "отрицательное", raw: "-1", want: 0},
		{name: "дата", raw: "2026-08-08", want: day},
		{name: "дата со временем", raw: "2026-08-08 12:30:00", want: moment},
		{name: "дата через T", raw: "2026-08-08T12:30:00", want: moment},
		{name: "RFC3339", raw: "2026-08-08T12:30:00Z", want: moment},
		{name: "пробелы вокруг", raw: "  2026-08-08  ", want: day},
		{name: "мусор", raw: "скоро", want: 0},
	}

	for _, item := range cases {
		t.Run(item.name, func(t *testing.T) {
			if got := parseRefillDate(item.raw); got != item.want {
				t.Fatalf("parseRefillDate(%q) = %d, ожидалось %d", item.raw, got, item.want)
			}
		})
	}
}

func TestValidateNewURL(t *testing.T) {
	const current = "https://panel.example.com/sub/token"

	cases := []struct {
		name      string
		current   string
		candidate string
		want      string
	}{
		{name: "пусто", current: current, candidate: "", want: ""},
		{name: "новый адрес", current: current, candidate: "https://new.example.com/sub/token", want: "https://new.example.com/sub/token"},
		{name: "тот же адрес переездом не считается", current: current, candidate: current, want: ""},
		// Понижение https → http запрещено: увести загрузку подписки на открытый
		// канал могла бы заголовком та же сторона, которую мы и проверяем.
		{name: "понижение до http", current: current, candidate: "http://new.example.com/sub", want: ""},
		{name: "http остаётся http", current: "http://panel.example.com/sub", candidate: "http://new.example.com/sub", want: "http://new.example.com/sub"},
		{name: "чужая схема", current: current, candidate: "ftp://new.example.com/sub", want: ""},
		{name: "javascript", current: current, candidate: "javascript:alert(1)", want: ""},
		{name: "без хоста", current: current, candidate: "new.example.com/sub", want: ""},
	}

	for _, item := range cases {
		t.Run(item.name, func(t *testing.T) {
			if got := validateNewURL(item.current, item.candidate); got != item.want {
				t.Fatalf("validateNewURL(%q, %q) = %q, ожидалось %q", item.current, item.candidate, got, item.want)
			}
		})
	}
}

func TestSwapDomain(t *testing.T) {
	const current = "https://panel.example.com/sub/token?key=1"

	cases := []struct {
		name    string
		current string
		domain  string
		want    string
	}{
		{name: "голый хост", current: current, domain: "new.example.com", want: "https://new.example.com/sub/token?key=1"},
		{name: "со схемой", current: current, domain: "https://new.example.com", want: "https://new.example.com/sub/token?key=1"},
		{name: "с хвостом пути", current: current, domain: "new.example.com/ignored", want: "https://new.example.com/sub/token?key=1"},
		{name: "с косой чертой на конце", current: current, domain: "new.example.com/", want: "https://new.example.com/sub/token?key=1"},
		{name: "с портом", current: current, domain: "new.example.com:8443", want: "https://new.example.com:8443/sub/token?key=1"},
		// Порт только числом в диапазоне: полная пробная загрузка по мусорному
		// кандидату стоит столько же, сколько по настоящему.
		{name: "порт буквами", current: current, domain: "new.example.com:abc", want: ""},
		{name: "порт вне диапазона", current: current, domain: "new.example.com:99999", want: ""},
		{name: "порт нулевой", current: current, domain: "new.example.com:0", want: ""},
		{name: "порт со знаком", current: current, domain: "new.example.com:+80", want: ""},
		{name: "порт пустой", current: current, domain: "new.example.com:", want: ""},
		// Режем по ПОСЛЕДНЕМУ двоеточию, иначе IPv6 превращается в мусор.
		{name: "IPv6 с портом", current: current, domain: "[2001:db8::1]:8443", want: "https://[2001:db8::1]:8443/sub/token?key=1"},
		{name: "IPv6 без порта", current: current, domain: "[2001:db8::1]", want: "https://[2001:db8::1]/sub/token?key=1"},
		{name: "тот же хост переездом не считается", current: current, domain: "panel.example.com", want: ""},
		{name: "пустой домен", current: current, domain: "", want: ""},
		{name: "пустой текущий адрес", current: "", domain: "new.example.com", want: ""},
	}

	for _, item := range cases {
		t.Run(item.name, func(t *testing.T) {
			if got := swapDomain(item.current, item.domain); got != item.want {
				t.Fatalf("swapDomain(%q, %q) = %q, ожидалось %q", item.current, item.domain, got, item.want)
			}
		})
	}
}

func TestHeaderValue(t *testing.T) {
	header := map[string][]string{
		"Profile-Title": {"Провайдер"},
		"X-Announce":    {"объявление"},
	}

	if got := headerValue(header, "profile-title"); got != "Провайдер" {
		t.Fatalf("точное совпадение имени: получено %q", got)
	}

	// Панели ставят одно и то же поле то как `announce`, то как `x-announce`.
	if got := headerValue(header, "announce"); got != "объявление" {
		t.Fatalf("совпадение по суффиксу: получено %q", got)
	}

	// Суффикс только по границе через дефис: `announce-url` не должен
	// отвечать на запрос `url` половиной чужого заголовка.
	if got := headerValue(map[string][]string{"Announceurl": {"нет"}}, "url"); got != "" {
		t.Fatalf("совпадение внутри слова недопустимо: получено %q", got)
	}

	if got := headerValue(header, "support-url"); got != "" {
		t.Fatalf("отсутствующий заголовок должен давать пустую строку, получено %q", got)
	}

	// Победитель выбирается по алфавиту, а не по порядку обхода map, иначе
	// баннер менялся бы сам по себе от запуска к запуску.
	both := map[string][]string{
		"announce":            {"первый"},
		"x-amz-meta-announce": {"второй"},
	}

	for i := 0; i < 20; i++ {
		if got := headerValue(both, "announce"); got != "первый" {
			t.Fatalf("выбор должен быть устойчивым, получено %q", got)
		}
	}
}

func TestDecodeHeaderValue(t *testing.T) {
	const text = "Привет, мир"

	// Кириллицу нельзя положить в заголовок сырыми байтами, поэтому панели
	// кодируют её — то стандартным алфавитом, то url-safe, то без выравнивания.
	for _, encoding := range []*base64.Encoding{
		base64.StdEncoding,
		base64.RawStdEncoding,
		base64.URLEncoding,
		base64.RawURLEncoding,
	} {
		raw := "base64:" + encoding.EncodeToString([]byte(text))

		if got := decodeHeaderValue(raw); got != text {
			t.Fatalf("%q разобрано как %q", raw, got)
		}
	}

	if got := decodeHeaderValue("  обычный текст  "); got != "обычный текст" {
		t.Fatalf("текст без префикса должен пройти как есть, получено %q", got)
	}

	// Значение объявило себя base64 и им не оказалось: литерал `base64:…`
	// в баннере хуже пустого места.
	if got := decodeHeaderValue("base64:!!!не base64!!!"); got != "" {
		t.Fatalf("битый base64 должен давать пустую строку, получено %q", got)
	}
}

func TestURLFilters(t *testing.T) {
	// Значение уходит прямо в Intent(ACTION_VIEW), то есть открывается одним
	// нажатием из содержимого, которым панель распоряжается целиком.
	if got := httpsURL("https://example.com/page"); got != "https://example.com/page" {
		t.Fatalf("https должен проходить, получено %q", got)
	}

	for _, value := range []string{"http://example.com", "javascript:alert(1)", "file:///etc/passwd", "intent://x", "https://", "", "  "} {
		if got := httpsURL(value); got != "" {
			t.Fatalf("httpsURL(%q) = %q, ожидалась пустая строка", value, got)
		}
	}

	// У поддержки есть свои законные схемы.
	for _, value := range []string{"https://t.me/support", "tg://resolve?domain=support", "mailto:help@example.com"} {
		if got := contactURL(value); got != value {
			t.Fatalf("contactURL(%q) = %q", value, got)
		}
	}

	for _, value := range []string{"http://t.me/support", "javascript:alert(1)", "https://", ""} {
		if got := contactURL(value); got != "" {
			t.Fatalf("contactURL(%q) = %q, ожидалась пустая строка", value, got)
		}
	}
}

func TestTruncate(t *testing.T) {
	// Считаем рунами, а не байтами: иначе кириллица резалась бы посреди символа.
	if got := truncate("абвгд", 3); got != "абв…" {
		t.Fatalf("truncate = %q", got)
	}

	if got := truncate("абв", 3); got != "абв" {
		t.Fatalf("строка по размеру не должна меняться, получено %q", got)
	}

	if got := truncate("аб  вг", 4); got != "аб…" {
		t.Fatalf("хвостовые пробелы должны срезаться, получено %q", got)
	}

	long := strings.Repeat("я", announceMaxChars+50)
	if got := []rune(truncate(long, announceMaxChars)); len(got) != announceMaxChars+1 {
		t.Fatalf("длина после обрезки %d рун", len(got))
	}
}

func TestHwidState(t *testing.T) {
	// Порядок проверок важен: Remnawave 3.x ставит `x-hwid-limit: true` ВСЕГДА,
	// а `x-hwid-max-devices-reached` — только при настоящем превышении.
	cases := []struct {
		name   string
		header map[string][]string
		want   string
	}{
		{name: "молчание", header: map[string][]string{}, want: HwidUnknown},
		{name: "устройство активно", header: map[string][]string{"x-hwid-active": {"true"}}, want: HwidActive},
		{name: "лимит исчерпан", header: map[string][]string{"x-hwid-max-devices-reached": {"true"}}, want: HwidLimitReached},
		{name: "панель ждёт идентификатор", header: map[string][]string{"x-hwid-not-supported": {"true"}, "x-hwid-limit": {"true"}}, want: HwidNotSupported},
		{name: "limit без reached — тоже лимит", header: map[string][]string{"x-hwid-limit": {"1"}}, want: HwidLimitReached},
		{name: "явное нет", header: map[string][]string{"x-hwid-active": {"false"}}, want: HwidUnknown},
	}

	for _, item := range cases {
		t.Run(item.name, func(t *testing.T) {
			if got := hwidState(item.header); got != item.want {
				t.Fatalf("hwidState = %q, ожидалось %q", got, item.want)
			}
		})
	}
}

func TestOptionalBool(t *testing.T) {
	// Молчание оставляет решение человеку, явный false снимает замок,
	// который мог стоять раньше, — это разные вещи.
	if got := optionalBool(map[string][]string{}, "clod-lock-mode"); got != nil {
		t.Fatalf("молчание должно давать nil, получено %v", *got)
	}

	for _, value := range []string{"true", "1", "YES", " on "} {
		got := optionalBool(map[string][]string{"clod-lock-mode": {value}}, "clod-lock-mode")
		if got == nil || !*got {
			t.Fatalf("%q должно читаться как true", value)
		}
	}

	for _, value := range []string{"false", "0", "No", "off"} {
		got := optionalBool(map[string][]string{"clod-lock-mode": {value}}, "clod-lock-mode")
		if got == nil || *got {
			t.Fatalf("%q должно читаться как false", value)
		}
	}

	if got := optionalBool(map[string][]string{"clod-lock-mode": {"может быть"}}, "clod-lock-mode"); got != nil {
		t.Fatalf("непонятное значение должно давать nil, получено %v", *got)
	}
}

func TestApplyHeaders(t *testing.T) {
	const current = "https://panel.example.com/sub/token"

	var info Info

	ApplyHeaders(&info, map[string][]string{
		"profile-title":            {"base64:" + base64.StdEncoding.EncodeToString([]byte("Провайдер"))},
		"profile-web-page-url":     {"https://example.com"},
		"support-url":              {"tg://resolve?domain=support"},
		"profile-logo":             {"http://example.com/logo.png"},
		"announce":                 {"Работы с 3 до 5"},
		"subscription-refill-date": {"1786309200"},
		"notify-expire-days":       {"7,3,1"},
		"notify-traffic-percent":   {"80,90,100"},
		"x-hwid-active":            {"true"},
		"x-hwid-max-devices":       {"5"},
		"new-domain":               {"new.example.com"},
		"global-mode":              {"false"},
	}, current)

	if info.Title != "Провайдер" {
		t.Fatalf("название = %q", info.Title)
	}

	if info.HomeURL != "https://example.com" {
		t.Fatalf("домашняя страница = %q", info.HomeURL)
	}

	if info.SupportURL != "tg://resolve?domain=support" {
		t.Fatalf("поддержка = %q", info.SupportURL)
	}

	// Логотип по http не берём вовсе.
	if info.LogoURL != "" {
		t.Fatalf("логотип по http не должен приниматься, получено %q", info.LogoURL)
	}

	if info.HwidState != HwidActive || info.HwidMaxDevices != 5 {
		t.Fatalf("состояние устройства = %q, лимит = %d", info.HwidState, info.HwidMaxDevices)
	}

	if info.RefillDate != 1786309200 {
		t.Fatalf("дата пополнения = %d", info.RefillDate)
	}

	if !reflect.DeepEqual(info.NotifyExpireDays, []int{1, 3, 7}) {
		t.Fatalf("пороги срока = %#v", info.NotifyExpireDays)
	}

	if !reflect.DeepEqual(info.NotifyTrafficPercent, []int{80, 90, 100}) {
		t.Fatalf("пороги трафика = %#v", info.NotifyTrafficPercent)
	}

	if info.MigrateURL != "https://new.example.com/sub/token" {
		t.Fatalf("переезд = %q", info.MigrateURL)
	}

	// `global-mode: false` у панелей под Prizrak-Box значит «спрячьте
	// переключатель режимов» — то же самое, что наш замок.
	if info.LockMode == nil || !*info.LockMode {
		t.Fatalf("замок режимов должен быть выставлен")
	}
}

func TestApplyHeadersNewURLWinsOverDomain(t *testing.T) {
	const current = "https://panel.example.com/sub/token"

	var info Info

	ApplyHeaders(&info, map[string][]string{
		"new-url":    {"https://first.example.com/sub"},
		"new-domain": {"second.example.com"},
	}, current)

	if info.MigrateURL != "https://first.example.com/sub" {
		t.Fatalf("адрес целиком приоритетнее домена, получено %q", info.MigrateURL)
	}
}

func TestApplyHeadersBareExpireToggle(t *testing.T) {
	var info Info

	// Совместимость с Happ: голый тумблер без списка включает умолчания.
	ApplyHeaders(&info, map[string][]string{"notification-subs-expire": {"true"}}, "https://panel.example.com/sub")

	if !reflect.DeepEqual(info.NotifyExpireDays, defaultNotifyExpireDays) {
		t.Fatalf("ожидались умолчания %#v, получено %#v", defaultNotifyExpireDays, info.NotifyExpireDays)
	}

	// Явный список сильнее тумблера.
	info = Info{}

	ApplyHeaders(&info, map[string][]string{
		"notification-subs-expire": {"true"},
		"notify-expire-days":       {"5"},
	}, "https://panel.example.com/sub")

	if !reflect.DeepEqual(info.NotifyExpireDays, []int{5}) {
		t.Fatalf("ожидался список из панели, получено %#v", info.NotifyExpireDays)
	}
}

func TestApplyHeadersResetsStateFields(t *testing.T) {
	// Состояние последнего ответа, а не накопленное знание: панель перестала
	// слать число устройств — значит его больше нет.
	info := Info{
		HwidState:            HwidActive,
		HwidMaxDevices:       5,
		RefillDate:           1786309200,
		NotifyExpireDays:     []int{1, 3, 7},
		NotifyTrafficPercent: []int{80},
		Title:                "Провайдер",
	}

	ApplyHeaders(&info, map[string][]string{}, "https://panel.example.com/sub")

	if info.HwidState != HwidUnknown || info.HwidMaxDevices != 0 || info.RefillDate != 0 {
		t.Fatalf("состояние устройства должно сбрасываться: %q %d %d", info.HwidState, info.HwidMaxDevices, info.RefillDate)
	}

	if info.NotifyExpireDays != nil || info.NotifyTrafficPercent != nil {
		t.Fatalf("пороги должны сбрасываться: %#v %#v", info.NotifyExpireDays, info.NotifyTrafficPercent)
	}

	// А вот название держится до следующего непустого: под ним подписка лежит
	// в списке, и остаться без имени из-за одного ответа она не должна.
	if info.Title != "Провайдер" {
		t.Fatalf("название не должно теряться, получено %q", info.Title)
	}
}

func TestApplyHeadersPanelTextsFollowPanel(t *testing.T) {
	// Объявление, промо, логотип и текст для диалога устройства — тоже
	// состояние последнего ответа. Снятое провайдером объявление висело бы
	// вечно, а логотип прежнего провайдера — поверх нового.
	info := Info{
		Title:            "Провайдер",
		LogoURL:          "https://old.example/logo.png",
		Announce:         "Работы с 3 до 5",
		AnnounceURL:      "https://old.example/news",
		Promo:            "Скидка 30%",
		PromoURL:         "https://old.example/sale",
		HwidLimitMessage: "Отвяжите старое устройство в кабинете",
	}

	ApplyHeaders(&info, map[string][]string{"profile-title": {"Провайдер"}}, "https://panel.example.com/sub")

	if info.Announce != "" || info.AnnounceURL != "" {
		t.Fatalf("объявление осталось: %q %q", info.Announce, info.AnnounceURL)
	}

	if info.Promo != "" || info.PromoURL != "" {
		t.Fatalf("промо осталось: %q %q", info.Promo, info.PromoURL)
	}

	if info.LogoURL != "" {
		t.Fatalf("логотип остался: %q", info.LogoURL)
	}

	if info.HwidLimitMessage != "" {
		t.Fatalf("текст для диалога устройства остался: %q", info.HwidLimitMessage)
	}

	if info.Title != "Провайдер" {
		t.Fatalf("название = %q", info.Title)
	}
}

func TestApplyHeadersClockSkew(t *testing.T) {
	var info Info

	// Час вперёд — как на телефоне со сбитыми часами.
	served := time.Now().UTC().Add(time.Hour)

	ApplyHeaders(&info, map[string][]string{
		"Date": {served.Format(http.TimeFormat)},
	}, "https://panel.example.com/sub")

	if info.ClockSkew < 3590 || info.ClockSkew > 3610 {
		t.Fatalf("поправка часов = %d, ожидалось около 3600", info.ClockSkew)
	}

	if info.ClockSkewAt == 0 {
		t.Fatalf("время измерения не проставлено")
	}

	// Ответ без `Date` ничего не говорит о часах: прошлое измерение остаётся.
	before := info.ClockSkew

	ApplyHeaders(&info, map[string][]string{}, "https://panel.example.com/sub")

	if info.ClockSkew != before {
		t.Fatalf("поправка часов не должна теряться: было %d, стало %d", before, info.ClockSkew)
	}
}

func TestInfoRoundTrip(t *testing.T) {
	dir := t.TempDir()

	// Файла нет — не ошибка: панель могла и не прислать ничего.
	if got := Read(dir); got.Title != "" {
		t.Fatalf("пустой каталог должен давать пустую структуру, получено %#v", got)
	}

	want := Info{
		Title:                "Провайдер",
		NotifyExpireDays:     []int{},
		NotifyTrafficPercent: []int{80, 90},
		LockMode:             new(bool),
	}

	Write(dir, want)

	got := Read(dir)

	// Пустой список должен пережить запись и чтение: с omitempty он стал бы
	// неотличим от молчания панели, и напоминания включились бы обратно.
	if got.NotifyExpireDays == nil || len(got.NotifyExpireDays) != 0 {
		t.Fatalf("выключенные напоминания не пережили запись: %#v", got.NotifyExpireDays)
	}

	if !reflect.DeepEqual(got.NotifyTrafficPercent, []int{80, 90}) {
		t.Fatalf("пороги трафика не пережили запись: %#v", got.NotifyTrafficPercent)
	}

	if got.LockMode == nil || *got.LockMode {
		t.Fatalf("явный false у замка режимов не пережил запись")
	}

	if got.Title != want.Title {
		t.Fatalf("название = %q", got.Title)
	}
}

func TestApplyHeadersShowZeroHosts(t *testing.T) {
	// Заголовок отключает НАШИ экраны «серверов нет»: узлы-обманки
	// показываются как есть. Поведение по умолчанию — экраны включены,
	// поэтому включать режим имеет право только внятное «да».
	for _, raw := range []string{"true", "TRUE", "1", "yes", "on"} {
		info := Info{}
		ApplyHeaders(&info, http.Header{"Clod-Show-0Hosts": []string{raw}}, "https://panel.example/sub")

		if !info.ShowZeroHosts {
			t.Fatalf("%q должно включать показ заглушек", raw)
		}
	}

	for _, raw := range []string{"", "  ", "false", "0", "off", "no", "мусор", "maybe"} {
		info := Info{}
		ApplyHeaders(&info, http.Header{"Clod-Show-0Hosts": []string{raw}}, "https://panel.example/sub")

		if info.ShowZeroHosts {
			t.Fatalf("%q не должно включать показ заглушек", raw)
		}
	}
}

func TestApplyHeadersShowZeroHostsFollowsPanel(t *testing.T) {
	// Поле — состояние последнего ответа, а не накопленное знание. Убрали
	// заголовок в панели — наши экраны возвращаются со следующим обновлением,
	// а не живут до переустановки приложения.
	info := Info{ShowZeroHosts: true}

	ApplyHeaders(&info, http.Header{"Profile-Title": []string{"Подписка"}}, "https://panel.example/sub")

	if info.ShowZeroHosts {
		t.Fatal("панель перестала слать заголовок — режим должен сняться")
	}
}

func TestApplyHeadersFallbackAddresses(t *testing.T) {
	// Запасные адреса — состояние последнего ответа: панель перестала их слать,
	// значит запасного адреса больше нет. Иначе клиент годами ходил бы
	// на домен, который провайдер давно отдал кому-то другому.
	info := Info{FallbackURL: "https://old.example/sub", FallbackDomain: "old.example"}

	ApplyHeaders(&info, http.Header{"Profile-Title": []string{"Подписка"}}, "https://panel.example/sub")

	if info.FallbackURL != "" || info.FallbackDomain != "" {
		t.Fatalf("молчание панели должно снимать запасные адреса, получено %q / %q", info.FallbackURL, info.FallbackDomain)
	}

	ApplyHeaders(&info, http.Header{
		"Fallback-Url":    []string{"https://backup.example/sub"},
		"Fallback-Domain": []string{" backup.example:8443 "},
	}, "https://panel.example/sub")

	if info.FallbackURL != "https://backup.example/sub" {
		t.Fatalf("fallback-url разобран неверно: %q", info.FallbackURL)
	}

	if info.FallbackDomain != "backup.example:8443" {
		t.Fatalf("fallback-domain разобран неверно: %q", info.FallbackDomain)
	}
}

func TestApplyHeadersFallbackURLRejectsPlainHTTP(t *testing.T) {
	// Запасной адрес — такой же адрес подписки, и правило то же: только https.
	var info Info

	ApplyHeaders(&info, http.Header{"Fallback-Url": []string{"http://backup.example/sub"}}, "https://panel.example/sub")

	if info.FallbackURL != "" {
		t.Fatalf("http-адрес принимать нельзя, получено %q", info.FallbackURL)
	}
}

func TestSpareAddresses(t *testing.T) {
	const current = "https://panel.example/sub/token"

	info := Info{
		FallbackURL:    "https://backup.example/sub",
		FallbackDomain: "spare.example",
	}

	got := info.SpareAddresses(current)
	want := []string{"https://backup.example/sub", "https://spare.example/sub/token"}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("порядок и состав запасных адресов: получено %#v, ожидалось %#v", got, want)
	}
}

func TestSpareAddressesSkipsCurrentAndEmpty(t *testing.T) {
	const current = "https://panel.example/sub"

	// Запасной адрес, равный основному, повторять незачем: он только что
	// не ответил. То же и с доменом, который уже стоит в адресе.
	info := Info{FallbackURL: current, FallbackDomain: "panel.example"}

	if got := info.SpareAddresses(current); len(got) != 0 {
		t.Fatalf("совпадающие с основным адреса должны отбрасываться, получено %#v", got)
	}

	if got := (Info{}).SpareAddresses(current); len(got) != 0 {
		t.Fatalf("без заголовков запасных адресов быть не должно, получено %#v", got)
	}
}

func TestDescription(t *testing.T) {
	if got := Description("  Франкфурт, 10 Гбит  "); got != "Франкфурт, 10 Гбит" {
		t.Fatalf("описание должно обрезаться по краям, получено %q", got)
	}

	for _, raw := range []any{nil, 42, "", "   ", map[string]any{}} {
		if got := Description(raw); got != "" {
			t.Fatalf("%#v должно давать пустое описание, получено %q", raw, got)
		}
	}

	// Длинное режется: в строке списка его всё равно негде показать.
	// `truncate` дописывает многоточие, поэтому длина на один знак больше.
	long := strings.Repeat("я", descriptionMaxChars+20)
	got := Description(long)

	if len([]rune(got)) > descriptionMaxChars+1 {
		t.Fatalf("описание должно резаться до %d символов, получено %d", descriptionMaxChars, len([]rune(got)))
	}

	if !strings.HasSuffix(got, "…") {
		t.Fatalf("у обрезанного описания должно быть многоточие, получено %q", got)
	}
}

func TestApplyHeadersProviderLinks(t *testing.T) {
	// Пять ссылок провайдера, те же, что на ПК. Проверка у бота своя: адрес
	// у него почти всегда `tg:`, а мониторинг и инструкция — обычные
	// страницы, и ничего, кроме https, за ними быть не должно.
	const current = "https://panel.example.com/sub/token"

	var info Info

	ApplyHeaders(&info, map[string][]string{
		"clod-portal-url":  {"https://provider.example/cabinet"},
		"support-url":      {"https://t.me/provider_support"},
		"clod-bot-url":     {"tg://resolve?domain=provider_bot"},
		"clod-monitor-url": {"https://status.provider.example"},
		"clod-guide-url":   {"https://provider.example/help/setup"},
	}, current)

	if info.PortalURL != "https://provider.example/cabinet" {
		t.Fatalf("кабинет = %q", info.PortalURL)
	}

	if info.SupportURL != "https://t.me/provider_support" {
		t.Fatalf("поддержка = %q", info.SupportURL)
	}

	if info.BotURL != "tg://resolve?domain=provider_bot" {
		t.Fatalf("бот = %q", info.BotURL)
	}

	if info.MonitorURL != "https://status.provider.example" {
		t.Fatalf("мониторинг = %q", info.MonitorURL)
	}

	if info.GuideURL != "https://provider.example/help/setup" {
		t.Fatalf("инструкция = %q", info.GuideURL)
	}

	// Бот принимает и обычную ссылку, и почту — как поддержка.
	for _, raw := range []string{"https://t.me/provider_bot", "mailto:bot@provider.example"} {
		var one Info

		ApplyHeaders(&one, map[string][]string{"clod-bot-url": {raw}}, current)

		if one.BotURL != raw {
			t.Fatalf("бот по %q = %q", raw, one.BotURL)
		}
	}

	// А мониторингу и инструкции ни `tg:`, ни голый http не годятся.
	var strict Info

	ApplyHeaders(&strict, map[string][]string{
		"clod-monitor-url": {"tg://resolve?domain=status"},
		"clod-guide-url":   {"http://provider.example/help"},
		"clod-bot-url":     {"javascript:alert(1)"},
	}, current)

	if strict.MonitorURL != "" || strict.GuideURL != "" || strict.BotURL != "" {
		t.Fatalf(
			"негодные ссылки прошли: бот %q, мониторинг %q, инструкция %q",
			strict.BotURL, strict.MonitorURL, strict.GuideURL,
		)
	}

	// Кириллицу и длинные адреса панели шлют через base64, и ссылки тут
	// ничем не отличаются от остальных заголовков.
	var encoded Info

	ApplyHeaders(&encoded, map[string][]string{
		"x-amz-meta-clod-monitor-url": {
			"base64:" + base64.StdEncoding.EncodeToString([]byte("https://status.provider.example/страница")),
		},
	}, current)

	if !strings.HasPrefix(encoded.MonitorURL, "https://status.provider.example/") {
		t.Fatalf("мониторинг из base64 = %q", encoded.MonitorURL)
	}
}

func TestApplyHeadersLinksVanishWhenPanelStops(t *testing.T) {
	// Ссылки провайдера — состояние последнего ответа. Панель перестала слать
	// заголовок — строки в настройках не должно быть тем же обновлением.
	// Сохранённая ссылка пережила бы и смену тарифа, и подмену ссылки
	// подписки на другого провайдера — и увела бы человека в чужой кабинет.
	info := Info{
		Title:      "Провайдер",
		PortalURL:  "https://old.example/cabinet",
		SupportURL: "https://t.me/old_support",
		HomeURL:    "https://old.example/sub",
		BotURL:     "tg://resolve?domain=old_bot",
		MonitorURL: "https://status.old.example",
		GuideURL:   "https://old.example/help",
	}

	// Ответ следующего провайдера: у него есть только кабинет и бот.
	ApplyHeaders(&info, map[string][]string{
		"clod-portal-url": {"https://new.example/cabinet"},
		"clod-bot-url":    {"tg://resolve?domain=new_bot"},
	}, "https://panel.example.com/sub/token")

	if info.PortalURL != "https://new.example/cabinet" {
		t.Fatalf("кабинет = %q", info.PortalURL)
	}

	if info.BotURL != "tg://resolve?domain=new_bot" {
		t.Fatalf("бот = %q", info.BotURL)
	}

	if info.SupportURL != "" || info.HomeURL != "" || info.MonitorURL != "" || info.GuideURL != "" {
		t.Fatalf(
			"чужие ссылки остались: поддержка %q, страница %q, мониторинг %q, инструкция %q",
			info.SupportURL, info.HomeURL, info.MonitorURL, info.GuideURL,
		)
	}

	// Ответ вовсе без ссылок не оставляет ни одной.
	ApplyHeaders(&info, map[string][]string{"profile-title": {"Провайдер"}}, "https://panel.example.com/sub/token")

	if info.PortalURL != "" || info.BotURL != "" {
		t.Fatalf("ссылки должны исчезать целиком: кабинет %q, бот %q", info.PortalURL, info.BotURL)
	}

	// А название держится: имя подписки между обновлениями пропадать не должно.
	if info.Title != "Провайдер" {
		t.Fatalf("название = %q", info.Title)
	}
}

func TestApplyHeadersBadLinkDropsOldOne(t *testing.T) {
	// Негодная ссылка (http, javascript:) — это НЕ «оставь прошлую»: панель
	// заголовок прислала, просто он никуда не годится, и открывать по нему
	// нечего. Иначе кривая настройка панели навсегда прибивала бы старый адрес.
	info := Info{MonitorURL: "https://status.old.example"}

	ApplyHeaders(&info, map[string][]string{
		"clod-monitor-url": {"http://status.new.example"},
	}, "https://panel.example.com/sub")

	if info.MonitorURL != "" {
		t.Fatalf("мониторинг = %q, ожидалась пустая строка", info.MonitorURL)
	}
}
