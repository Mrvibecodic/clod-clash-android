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
	"cfa/native/config/sentinel"

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
	Raw                   map[string][]string
}

func subscriptionHeaders() http.Header {
	header := http.Header{
		"User-Agent": {"ClodClash/" + app.VersionName() + " (Android)"},
		"Accept":     {"*/*"},
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

func fetchConfig(url *U.URL, file string) (fetchHeader, error) {
	if !SecureChannel() || (url.Scheme != "http" && url.Scheme != "https") {
		return fetch(url, file)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	reader, header, err := openUrlSecure(ctx, url.String(), P.Dir(file))
	if err != nil {
		return fetchHeader{}, err
	}

	defer reader.Close()

	return header, writeFile(file, reader)
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

func fetchFromSpare(
	info PanelInfo,
	primary *U.URL,
	configPath string,
	cause error,
	reportStatus func(string),
) (fetchHeader, error) {
	spares := info.SpareAddresses(primary.String())
	if len(spares) == 0 {
		return fetchHeader{}, cause
	}

	log.Warnln("Subscription address failed (%s), trying %d spare address(es) from the provider", cause.Error(), len(spares))

	for _, spare := range spares {
		parsed, err := U.Parse(spare)
		if err != nil {
			continue
		}

		bytes, _ := json.Marshal(&Status{
			Action:      "FetchConfiguration",
			Args:        []string{parsed.Host},
			Progress:    -1,
			MaxProgress: -1,
		})

		reportStatus(string(bytes))

		header, err := fetchConfig(parsed, configPath)
		if err != nil {
			log.Warnln("Spare address %s failed as well: %s", parsed.Host, err.Error())

			continue
		}

		log.Infoln("Subscription fetched from the spare address %s", parsed.Host)

		return header, nil
	}

	return fetchHeader{}, cause
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

		info := readPanelInfo(path)

		header, err := fetchConfig(url, configPath)
		if err != nil {
			header, err = fetchFromSpare(info, url, configPath, err, reportStatus)
			if err != nil {
				return err
			}
		}

		reportSubscriptionInfo(header, reportStatus)

		applyHeaders(&info, header.Raw, url.String())
		info.LogoFile = fetchLogo(path, info.LogoURL)
		writePanelInfo(path, info)

		if err := deviceRefused(info); err != nil {
			return err
		}
	}

	defer runtime.GC()

	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		return err
	}

	panelInfo := readPanelInfo(path)
	applyGroups(&panelInfo, rawCfg)

	report := sentinel.Inspect(rawCfg.Proxy)

	panelInfo.NoServers = report.OnlySentinels && !panelInfo.ShowZeroHosts

	if panelInfo.ShowZeroHosts {
		panelInfo.Sentinels = nil
	} else {
		panelInfo.Sentinels = report.Names
	}

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
