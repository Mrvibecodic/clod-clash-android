package config

import (
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/config"
)

// Узел-обманка, каким его отдают вместо серверов.
//
// Сервис подписки, когда выдавать нечего — срок вышел, трафик кончился,
// подписка отключена, серверы не настроены, устройств больше лимита, — НЕ
// отвечает ошибкой. Он отвечает HTTP 200 и валидной конфигурацией, в которой
// вместо серверов лежат узлы `server: 0.0.0.0`, `port: 1`,
// `uuid: 00000000-…`. Названия у них произвольные: они лежат в базе сервиса,
// владелец переписывает их под себя и на своём языке, — поэтому проверка
// только структурная, по названиям опознавать НЕЛЬЗЯ.
//
// Показать такое как список серверов означает предложить человеку подключиться
// к тому, к чему подключиться нельзя, и ни словом не объяснить, что случилось.
const nilUUID = "00000000-0000-0000-0000-000000000000"

// Локальные типы mihomo живут без адреса и порта — это не заглушки.
var serverlessTypes = map[string]bool{
	"direct": true, "reject": true, "reject-drop": true, "pass": true, "dns": true,
}

// Секреты настоящего узла. Ключевые протоколы (wireguard, ssh) авторизуются
// не паролем, а ключом — без этой оговорки живой узел на первом порту
// (порт легальный) уехал бы в заглушки.
var credentialKeys = []string{"uuid", "password", "psk", "private-key", "auth", "auth-str", "token"}

func isSentinelProxy(proxy map[string]any) bool {
	if kind, ok := proxy["type"].(string); ok && serverlessTypes[strings.ToLower(strings.TrimSpace(kind))] {
		return false
	}

	unspecifiedHost := false

	switch host := proxy["server"].(type) {
	case string:
		switch strings.TrimSpace(host) {
		case "", "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0":
			unspecifiedHost = true
		}
	case nil:
		_, present := proxy["server"]
		unspecifiedHost = present
	}

	nilID := false

	if id, ok := proxy["uuid"].(string); ok {
		nilID = strings.EqualFold(strings.TrimSpace(id), nilUUID)
	}

	// Неуказанный адрес и нулевой идентификатор — приговор сами по себе: такой
	// узел не может ни соединиться, ни авторизоваться. А «мёртвый порт» —
	// признак слишком слабый, чтобы выкидывать по нему живой узел, поэтому
	// он считается только вместе с отсутствием любых секретов.
	return unspecifiedHost || nilID || (deadPort(proxy) && missingCredentials(proxy))
}

func deadPort(proxy map[string]any) bool {
	value, present := proxy["port"]
	if !present {
		return false
	}

	switch port := value.(type) {
	case int:
		return port <= 1
	case int64:
		return port <= 1
	case float64:
		return port <= 1
	case string:
		parsed, err := strconv.Atoi(strings.TrimSpace(port))

		return err != nil || parsed <= 1
	}

	return true
}

func missingCredentials(proxy map[string]any) bool {
	for _, key := range credentialKeys {
		if value, ok := proxy[key].(string); ok && strings.TrimSpace(value) != "" {
			return false
		}
	}

	return true
}

// SentinelReport — что фильтр увидел в конфигурации.
type SentinelReport struct {
	// Названия выброшенных узлов — как их назвал владелец сервиса.
	// Показывать их человеку не обязательно, но в логи они попадают:
	// по ним видно, что именно сервис пытался сказать.
	Remarks []string
	// После чистки не осталось ни одного настоящего узла.
	OnlySentinels bool
}

// Сколько названий имеет смысл запомнить: сервис шлёт их по одному
// на строку своего сообщения.
const maxReportedRemarks = 4

func inspectSentinels(cfg *config.RawConfig) SentinelReport {
	report := SentinelReport{}

	if cfg == nil || len(cfg.Proxy) == 0 {
		return report
	}

	real := 0

	for _, proxy := range cfg.Proxy {
		if !isSentinelProxy(proxy) {
			real++

			continue
		}

		if name, ok := proxy["name"].(string); ok && name != "" && len(report.Remarks) < maxReportedRemarks {
			report.Remarks = append(report.Remarks, name)
		}
	}

	report.OnlySentinels = real == 0 && len(cfg.Proxy) > 0

	return report
}

// filterSentinels выбрасывает узлы-обманки из конфигурации до того, как она
// уедет в ядро.
//
// Оставить их означало бы показать человеку список серверов, к которым нельзя
// подключиться, и дать ядру возможность на них переключиться. Из групп имена
// тоже убираются: иначе группа осталась бы ссылаться на несуществующий узел.
func filterSentinels(cfg *config.RawConfig, _ string) error {
	if len(cfg.Proxy) == 0 {
		return nil
	}

	dropped := map[string]bool{}
	kept := make([]map[string]any, 0, len(cfg.Proxy))

	for _, proxy := range cfg.Proxy {
		if !isSentinelProxy(proxy) {
			kept = append(kept, proxy)

			continue
		}

		if name, ok := proxy["name"].(string); ok {
			dropped[name] = true
		}
	}

	if len(dropped) == 0 {
		return nil
	}

	cfg.Proxy = kept

	for _, group := range cfg.ProxyGroup {
		names, ok := group["proxies"].([]any)
		if !ok {
			continue
		}

		filtered := make([]any, 0, len(names))

		for _, raw := range names {
			if name, ok := raw.(string); ok && dropped[name] {
				continue
			}

			filtered = append(filtered, raw)
		}

		// Группа без единого узла ядро не примет. Оставляем ей `DIRECT`:
		// подключение через него не пойдёт мимо туннеля, потому что туннеля
		// в этом состоянии и нет, — зато конфигурация остаётся разбираемой,
		// и экран успевает объяснить, что произошло.
		if len(filtered) == 0 {
			filtered = append(filtered, "DIRECT")
		}

		group["proxies"] = filtered
	}

	return nil
}
