package config

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	U "net/url"
	"os"
	P "path"
	"runtime"
	"strconv"
	"strings"
	"time"

	"cfa/native/app"

	"github.com/metacubex/mihomo/adapter/provider"
	clashHttp "github.com/metacubex/mihomo/component/http"
	"github.com/metacubex/mihomo/log"
	RB "github.com/metacubex/mihomo/rules/bundle"
)

type Status struct {
	Action            string   `json:"action"`
	Args              []string `json:"args"`
	Progress          int      `json:"progress"`
	MaxProgress       int      `json:"max"`
	SubUpload         *int64   `json:"subUpload,omitempty"`
	SubDownload       *int64   `json:"subDownload,omitempty"`
	SubTotal          *int64   `json:"subTotal,omitempty"`
	SubExpire         *int64   `json:"subExpire,omitempty"`
	SubUpdateInterval *int64   `json:"subUpdateInterval,omitempty"`
}

type fetchHeader struct {
	SubscriptionUserInfo  string
	ProfileUpdateInterval string
	// Полный набор заголовков ответа: из него берутся название подписки,
	// объявления и ссылки панели (см. panel.go). Держать их по одному полю
	// на заголовок — значит править эту структуру на каждый новый.
	//
	// Тип именно map[string][]string, а не http.Header: клиент ядра построен
	// на форке github.com/metacubex/http, и его Header — другой именованный тип
	// с тем же основанием, так что присвоение требует явного преобразования.
	Raw map[string][]string
}

// subscriptionHeaders — то, чем клиент представляется панели.
//
// `User-Agent` — ровно один на всё приложение: панель Remnawave показывает его
// в списке устройств рядом с `x-hwid`, и два разных UA от одного клиента она
// считает двумя устройствами (грабля koala-clash). Формат `имя/версия`
// с пометкой платформы: имя матчится регулярками правил подписки
// с учётом регистра, поэтому оно захардкожено, а не собирается из названия.
//
// Заголовки опознания устройства добавляются, только если оно включено:
// см. `app.DeviceHeaders`.
func subscriptionHeaders() http.Header {
	header := http.Header{
		"User-Agent": {"ClodClash/" + app.VersionName() + " (Android)"},
		// Обязателен: правила ответа Remnawave отдают браузерам HTML-страницу,
		// а не конфигурацию, и опознают их как раз по `Accept`.
		"Accept": {"*/*"},
	}

	for name, value := range app.DeviceHeaders() {
		header.Set(name, value)
	}

	return header
}

func openUrl(ctx context.Context, url string) (io.ReadCloser, fetchHeader, error) {
	response, err := clashHttp.HttpRequest(ctx, url, http.MethodGet, subscriptionHeaders(), nil)

	if err != nil {
		return nil, fetchHeader{}, err
	}

	return response.Body, fetchHeader{
		SubscriptionUserInfo:  response.Header.Get("subscription-userinfo"),
		ProfileUpdateInterval: response.Header.Get("profile-update-interval"),
		Raw:                   map[string][]string(response.Header),
	}, nil
}

func openContent(url string) (io.ReadCloser, error) {
	return app.OpenContent(url)
}

func fetch(url *U.URL, file string) (fetchHeader, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	var reader io.ReadCloser
	var header fetchHeader
	var err error

	switch url.Scheme {
	case "http", "https":
		reader, header, err = openUrl(ctx, url.String())
	case "content":
		reader, err = openContent(url.String())
	default:
		err = fmt.Errorf("unsupported scheme %s of %s", url.Scheme, url)
	}

	if err != nil {
		return fetchHeader{}, err
	}

	defer reader.Close()

	return header, writeFile(file, reader)
}

func writeFile(file string, reader io.Reader) error {
	_ = os.MkdirAll(P.Dir(file), 0700)

	f, err := os.OpenFile(file, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		return err
	}

	defer f.Close()

	_, err = io.Copy(f, reader)
	if err != nil {
		_ = os.Remove(file)
	}

	return err
}

func parseProfileUpdateInterval(value string) (int64, bool) {
	hours, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil {
		return 0, false
	}

	if hours <= 0 {
		return 0, true
	}

	interval := time.Duration(hours) * time.Hour
	if interval < 15*time.Minute {
		interval = 15 * time.Minute
	}

	return int64(interval / time.Millisecond), true
}

func reportSubscriptionInfo(header fetchHeader, reportStatus func(string)) {
	userinfo := header.SubscriptionUserInfo
	updateIntervalHeader := header.ProfileUpdateInterval
	if userinfo == "" && updateIntervalHeader == "" {
		return
	}

	status := Status{
		Action:      "SubscriptionInfo",
		Args:        []string{},
		Progress:    -1,
		MaxProgress: -1,
	}

	if userinfo != "" {
		info := provider.NewSubscriptionInfo(userinfo)

		// `subscription-userinfo` требует секунды, но панели встречаются и с
		// миллисекундами. Умножение вслепую давало бы срок в пятидесятитысячном
		// году: карточка подписки показывала бы бессмыслицу, а напоминания
		// о конце срока молчали бы вечно. Граница 10^12 — это 2001 год
		// в секундах и 1970-й в миллисекундах, ошибиться нечем.
		expire := info.Expire
		if expire > 0 && expire < 1_000_000_000_000 {
			expire *= 1000
		}
		status.SubUpload = &info.Upload
		status.SubDownload = &info.Download
		status.SubTotal = &info.Total
		status.SubExpire = &expire
	}

	if interval, ok := parseProfileUpdateInterval(updateIntervalHeader); ok {
		status.SubUpdateInterval = &interval
	}

	bytes, _ := json.Marshal(&status)
	reportStatus(string(bytes))
}

func FetchAndValid(
	path string,
	url string,
	force bool,
	reportStatus func(string),
) error {
	configPath := P.Join(path, "config.yaml")

	if _, err := os.Stat(configPath); os.IsNotExist(err) || force {
		url, err := U.Parse(url)
		if err != nil {
			return err
		}

		bytes, _ := json.Marshal(&Status{
			Action:      "FetchConfiguration",
			Args:        []string{url.Host},
			Progress:    -1,
			MaxProgress: -1,
		})

		reportStatus(string(bytes))

		header, err := fetch(url, configPath)
		if err != nil {
			return err
		}

		reportSubscriptionInfo(header, reportStatus)

		// Заголовки панели сохраняем сразу: дальше по коду ответа уже нет,
		// а разбор конфига может и не дойти до конца.
		info := readPanelInfo(path)
		applyHeaders(&info, header.Raw)
		// Логотип провайдера кладём рядом с конфигом сразу же. Тянуть его с
		// чужого хоста на каждую отрисовку экрана значило бы отдавать этому
		// хосту адрес человека и мигать пустотой на холодном старте и офлайн.
		info.LogoFile = fetchLogo(path, info.LogoURL)
		writePanelInfo(path, info)

		// Панель отказалась выдать конфигурацию этому устройству — значит
		// в теле ответа заглушка: узлы там с адресом 0.0.0.0. Записать её
		// поверх рабочей подписки означает отнять у человека рабочие серверы
		// за то, что он подключил лишний телефон. Обновление отменяем.
		//
		// Файл уже перезаписан, но это `processingDir`: в рабочий каталог
		// он переезжает только после успеха, а на ошибке выбрасывается.
		if err := deviceRefused(info); err != nil {
			return err
		}
	}

	defer runtime.GC()

	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		return err
	}

	// Состав групп и узлов кладём рядом с конфигом: пока туннель не поднят,
	// ядро о них ничего не знает, а список серверов нужен и до подключения.
	panelInfo := readPanelInfo(path)
	applyGroups(&panelInfo, rawCfg)

	// Узлы-обманки к этому месту уже выброшены фильтром (`filterSentinels`
	// в списке процессоров), но факт «настоящих серверов не было ни одного»
	// экрану нужен: без него пустой список выглядит как поломка приложения,
	// а не как отказ сервиса.
	report := inspectSentinels(rawCfg)
	panelInfo.NoServers = report.OnlySentinels

	if len(report.Remarks) > 0 {
		log.Infoln("Subscription sent placeholders instead of servers: %s", strings.Join(report.Remarks, " | "))
	}

	writePanelInfo(path, panelInfo)

	forEachProviders(rawCfg, func(index int, total int, name string, provider map[string]any, prefix string) {
		bytes, _ := json.Marshal(&Status{
			Action:      "FetchProviders",
			Args:        []string{name},
			Progress:    index,
			MaxProgress: total,
		})

		reportStatus(string(bytes))

		u, uok := provider["url"]
		p, pok := provider["path"]

		if !uok || !pok {
			return
		}

		us, uok := u.(string)
		ps, pok := p.(string)

		if !uok || !pok {
			return
		}

		if _, err := os.Stat(ps); err == nil {
			return
		}

		url, err := U.Parse(us)
		if err != nil {
			return
		}

		if prefix == RULES {
			if pib, uok := provider["path-in-bundle"]; uok {
				if pib, uok := pib.(string); uok && pib != "" {
					// actually, we don't need to extract the file here; the core will do it.
					// however, due to historical reasons, CMFA fetches provider content when loading profile,
					// so we maintain consistency with the old behavior.
					if file, err := RB.Open(pib); err == nil {
						defer file.Close()
						if err := writeFile(ps, file); err == nil {
							return
						}
					}
				}
			}
		}

		_, _ = fetch(url, ps)
	})

	bytes, _ := json.Marshal(&Status{
		Action:      "Verifying",
		Args:        []string{},
		Progress:    0xffff,
		MaxProgress: 0xffff,
	})

	reportStatus(string(bytes))

	cfg, err := Parse(rawCfg)
	if err != nil {
		return err
	}

	DestroyProviders(cfg)

	return nil
}

// Ошибки отказа панели по устройству. Разбираются на стороне Kotlin
// и превращаются в человеческий текст — переводов в ядре нет.
var (
	ErrHwidLimitReached = errors.New("clod-device-limit")
	ErrHwidNotSupported = errors.New("clod-device-not-identified")
)

func deviceRefused(info PanelInfo) error {
	switch info.HwidState {
	case HwidLimitReached:
		return ErrHwidLimitReached
	case HwidNotSupported:
		return ErrHwidNotSupported
	}

	return nil
}
