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
	"sync"
	"sync/atomic"
	"time"

	"cfa/native/app"
	"cfa/native/common/safego"
	budgets "cfa/native/config/budget"
	"cfa/native/config/delivery"
	"cfa/native/config/sentinel"

	"github.com/metacubex/mihomo/adapter/provider"
	clashHttp "github.com/metacubex/mihomo/component/http"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
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

const directOutbound = "DIRECT"

func refusedByRedirectPolicy(err error) bool {
	return errors.Is(err, clashHttp.ErrRedirectDowngrade) || errors.Is(err, clashHttp.ErrTooManyRedirects)
}

const (
	fetchTimeout      = 60 * time.Second
	fetchQuickTimeout = 30 * time.Second
	providerTimeout   = 30 * time.Second
	prefetchBudget    = 20 * time.Second
	providerParallel  = 4
)

func subscriptionHeaders(device bool) http.Header {
	header := http.Header{
		"User-Agent": {"ClodClash/" + app.VersionName() + " (Android)"},
		"Accept":     {"*/*"},
	}

	if !device {
		return header
	}

	for name, value := range app.DeviceHeaders() {
		header.Set(name, value)
	}

	return header
}

type addressWindow struct {
	tunnel   time.Duration
	deadline time.Time
}

func openAddressWindow(budget *budgets.Budget, limit time.Duration, device bool) (addressWindow, bool) {
	now := time.Now()

	window, ok := budget.Window(now, limit)
	if !ok {
		return addressWindow{}, false
	}

	tunnel := window
	if device {
		tunnel = budgets.TunnelShare(window)
	}

	return addressWindow{tunnel: tunnel, deadline: now.Add(window)}, true
}

type roundBudget struct {
	budget   *budgets.Budget
	deadline time.Time
	cancels  []context.CancelFunc
}

func (b *roundBudget) start() (context.Context, bool) {
	limit, ok := b.budget.DirectWindow(time.Now(), b.deadline, 0)
	if !ok {
		return nil, false
	}

	ctx, cancel := context.WithTimeout(context.Background(), budgets.TunnelShare(limit))

	b.cancels = append(b.cancels, cancel)

	return ctx, true
}

func (b *roundBudget) close() {
	for _, cancel := range b.cancels {
		cancel()
	}
}

type directBudget struct {
	budget   *budgets.Budget
	deadline time.Time
	reserved bool
	cancels  []context.CancelFunc
}

func (b *directBudget) start() (context.Context, bool) {
	minimum := time.Duration(0)
	if !b.reserved {
		minimum = budgets.DirectReserve
	}

	limit, ok := b.budget.DirectWindow(time.Now(), b.deadline, minimum)
	if !ok {
		return nil, false
	}

	b.reserved = true

	ctx, cancel := context.WithTimeout(context.Background(), limit)

	b.cancels = append(b.cancels, cancel)

	return ctx, true
}

func (b *directBudget) close() {
	for _, cancel := range b.cancels {
		cancel()
	}
}

func openUrl(ctx context.Context, direct *directBudget, url string, device bool) (io.ReadCloser, fetchHeader, error) {
	options := []clashHttp.Option{}
	bypassed := false

	if device && keptOutOfTunnel(url) {
		if ctxDirect, ok := direct.start(); ok {
			log.Infoln("The rules of the profile keep the subscription address out of the tunnel, requesting it directly")

			ctx = ctxDirect
			options = append(options, clashHttp.WithSpecialProxy(directOutbound))
			bypassed = true
		}
	}

	response, err := clashHttp.HttpRequest(ctx, url, http.MethodGet, subscriptionHeaders(device), nil, options...)

	if err != nil && device && !bypassed && !refusedByRedirectPolicy(err) {
		tunnelErr := err

		direct, ok := direct.start()
		if !ok {
			log.Warnln("Subscription request failed through the tunnel (%s), no time left for a direct retry", tunnelErr.Error())

			return nil, fetchHeader{}, tunnelErr
		}

		log.Warnln("Subscription request failed through the tunnel (%s), retrying directly", tunnelErr.Error())

		response, err = clashHttp.HttpRequest(
			direct,
			url,
			http.MethodGet,
			subscriptionHeaders(device),
			nil,
			clashHttp.WithSpecialProxy(directOutbound),
		)

		if err != nil {
			return nil, fetchHeader{}, tunnelErr
		}
	}

	if err != nil {
		return nil, fetchHeader{}, err
	}

	if device && response.Request != nil {
		warnOnForeignRedirect(url, response.Request.URL)
	}

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		_ = response.Body.Close()

		return nil, fetchHeader{}, fmt.Errorf("server answered with status %d", response.StatusCode)
	}

	body := io.ReadCloser(response.Body)

	if device {
		guarded, err := delivery.Guard(response.Body)
		if err != nil {
			_ = response.Body.Close()

			return nil, fetchHeader{}, err
		}

		body = guarded
	}

	return body, fetchHeader{
		SubscriptionUserInfo:  response.Header.Get("subscription-userinfo"),
		ProfileUpdateInterval: response.Header.Get("profile-update-interval"),
		Raw:                   map[string][]string(response.Header),
	}, nil
}

func openContent(url string) (io.ReadCloser, error) {
	return app.OpenContent(url)
}

func fetchConfig(url *U.URL, file string, budget *budgets.Budget, limit time.Duration) (fetchHeader, error) {
	if !SecureChannel() || (url.Scheme != "http" && url.Scheme != "https") {
		return fetch(url, file, true, budget, limit)
	}

	window, ok := openAddressWindow(budget, limit, true)
	if !ok {
		return fetchHeader{}, errFetchBudget
	}

	rounds := &roundBudget{budget: budget, deadline: window.deadline}
	defer rounds.close()

	direct := &directBudget{budget: budget, deadline: window.deadline}
	defer direct.close()

	reader, header, err := openUrlSecure(rounds, url.String(), P.Dir(file), direct)
	if err != nil {
		return fetchHeader{}, err
	}

	defer reader.Close()

	return header, writeFile(file, reader)
}

func hostOf(url *U.URL) string {
	host := url.Hostname()
	port := url.Port()

	if port == "" || (url.Scheme == "http" && port == "80") || (url.Scheme == "https" && port == "443") {
		return host
	}

	return host + ":" + port
}

func warnOnForeignRedirect(requested string, final *U.URL) {
	if final == nil {
		return
	}

	source, err := U.Parse(requested)
	if err != nil {
		return
	}

	if !strings.EqualFold(hostOf(final), hostOf(source)) {
		log.Warnln("Subscription redirected from %s to %s, device headers followed the redirect", source.Host, final.Host)
	}
}

var errFetchBudget = errors.New("time budget for the update is exhausted")

func fetch(url *U.URL, file string, device bool, budget *budgets.Budget, limit time.Duration) (fetchHeader, error) {
	window, ok := openAddressWindow(budget, limit, device)
	if !ok {
		return fetchHeader{}, errFetchBudget
	}

	ctx, cancel := context.WithTimeout(context.Background(), window.tunnel)
	defer cancel()

	direct := &directBudget{budget: budget, deadline: window.deadline}
	defer direct.close()

	var reader io.ReadCloser
	var header fetchHeader
	var err error

	switch url.Scheme {
	case "http", "https":
		reader, header, err = openUrl(ctx, direct, url.String(), device)
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

const maxDownloadBytes = 32 << 20

func writeFile(file string, reader io.Reader) error {
	_ = os.MkdirAll(P.Dir(file), 0700)

	f, err := os.OpenFile(file, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		return err
	}

	defer f.Close()

	written, err := io.Copy(f, io.LimitReader(reader, maxDownloadBytes+1))
	if err == nil && written > maxDownloadBytes {
		err = fmt.Errorf("response larger than %d bytes", maxDownloadBytes)
	}

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
	spares []string,
	configPath string,
	cause error,
	budget *budgets.Budget,
	reportStatus func(string),
) (fetchHeader, error) {
	if len(spares) == 0 {
		return fetchHeader{}, cause
	}

	log.Warnln("Subscription address failed (%s), trying %d spare address(es) from the provider", cause.Error(), len(spares))

	for index, spare := range spares {
		parsed, err := U.Parse(spare)
		if err != nil {
			continue
		}

		limit := fetchQuickTimeout
		if index == len(spares)-1 {
			limit = fetchTimeout
		}

		if budget.Remaining(time.Now()) < budgets.MinAttempt {
			log.Warnln("Spare address %s skipped: %s", parsed.Host, errFetchBudget.Error())

			break
		}

		bytes, _ := json.Marshal(&Status{
			Action:      "FetchConfiguration",
			Args:        []string{parsed.Host},
			Progress:    -1,
			MaxProgress: -1,
		})

		reportStatus(string(bytes))

		header, err := fetchConfig(parsed, configPath, budget, limit)
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
	probe bool,
	reportStatus func(string),
) error {
	configPath := P.Join(path, "config.yaml")

	total := budgets.Total
	if probe {
		total = budgets.MigrationPart
	}

	budget := budgets.Within(time.Now(), total)

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

		spares := info.SpareAddresses(url.String())

		limit := fetchTimeout
		if len(spares) > 0 {
			limit = fetchQuickTimeout
		}

		header, err := fetchConfig(url, configPath, budget, limit)
		if err != nil {
			header, err = fetchFromSpare(spares, configPath, err, budget, reportStatus)
			if err != nil {
				return err
			}
		}

		reportSubscriptionInfo(header, reportStatus)

		applyHeaders(&info, header.Raw, url.String())
		info.LogoFile = fetchLogo(path, info.LogoURL, budget)
		writePanelInfo(path, info)
	}

	defer runtime.GC()

	rawCfg, err := unmarshalProfile(path)
	if err != nil {
		return err
	}

	template := rawCfg.Mode

	if err := process(rawCfg, path); err != nil {
		return err
	}

	panelInfo := readPanelInfo(path)
	applyGroups(&panelInfo, rawCfg, template)

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

	fetchProviders(rawCfg, budget, reportStatus)

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

type providerJob struct {
	name   string
	url    *U.URL
	path   string
	bundle string
}

func providerJobs(rawCfg *config.RawConfig) ([]providerJob, int) {
	jobs := []providerJob{}
	paths := map[string]bool{}
	total := 0

	forEachProviders(rawCfg, func(index int, count int, name string, provider map[string]any, prefix string) {
		total = count

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

		if paths[ps] {
			return
		}

		paths[ps] = true

		url, err := U.Parse(us)
		if err != nil {
			return
		}

		job := providerJob{name: name, url: url, path: ps}

		if prefix == RULES {
			if pib, ok := provider["path-in-bundle"].(string); ok {
				job.bundle = pib
			}
		}

		jobs = append(jobs, job)
	})

	return jobs, total
}

func reportProvider(reportStatus func(string), action string, args []string, progress int, total int) {
	bytes, _ := json.Marshal(&Status{
		Action:      action,
		Args:        args,
		Progress:    progress,
		MaxProgress: total,
	})

	reportStatus(string(bytes))
}

func fetchProviders(rawCfg *config.RawConfig, budget *budgets.Budget, reportStatus func(string)) {
	jobs, total := providerJobs(rawCfg)

	if len(jobs) == 0 {
		return
	}

	reportProvider(reportStatus, "FetchProviders", []string{jobs[0].name}, total-len(jobs), total)

	budget.EnterProviderPhase()

	var done atomic.Int32

	slots := make(chan struct{}, providerParallel)

	wg := &sync.WaitGroup{}

	for _, job := range jobs {
		wg.Add(1)

		job := job

		safego.Go("fetchProvider", func() {
			defer wg.Done()

			slots <- struct{}{}
			defer func() { <-slots }()

			started := total - len(jobs) + int(done.Add(1))

			reportProvider(reportStatus, "FetchProviders", []string{job.name}, started, total)

			if err := fetchProvider(job, budget); err != nil {
				log.Warnln("Fetch provider %s: %s", job.name, err.Error())

				reportProvider(reportStatus, "ProviderFailed", []string{job.name, err.Error()}, started, total)
			}
		})
	}

	wg.Wait()
}

func fetchProvider(job providerJob, budget *budgets.Budget) error {
	_ = os.MkdirAll(P.Dir(job.path), 0700)

	parts := constant.Path.Resolve("provider-parts")

	_ = os.MkdirAll(parts, 0700)

	part, err := os.CreateTemp(parts, P.Base(job.path)+".*.part")
	if err != nil {
		return err
	}

	temp := part.Name()
	_ = part.Close()

	if err := fetchProviderTo(job, temp, budget); err != nil {
		_ = os.Remove(temp)

		return err
	}

	if err := os.Rename(temp, job.path); err != nil {
		_ = os.Remove(temp)

		return err
	}

	return nil
}

func fetchProviderTo(job providerJob, file string, budget *budgets.Budget) error {
	if job.bundle != "" {
		if bundled, err := RB.Open(job.bundle); err == nil {
			defer bundled.Close()

			if err := writeFile(file, bundled); err == nil {
				return nil
			}
		}
	}

	_, err := fetch(job.url, file, false, budget, providerTimeout)

	return err
}

func prefetchProviders(rawCfg *config.RawConfig) {
	fetchProviders(rawCfg, budgets.Within(time.Now(), prefetchBudget), func(string) {})
}
