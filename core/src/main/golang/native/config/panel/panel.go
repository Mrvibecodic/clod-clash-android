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

type Info struct {
	Title       string `json:"title,omitempty"`
	LogoURL     string `json:"logoUrl,omitempty"`
	Announce    string `json:"announce,omitempty"`
	AnnounceURL string `json:"announceUrl,omitempty"`
	SupportURL  string `json:"supportUrl,omitempty"`
	HomeURL     string `json:"homeUrl,omitempty"`
	PortalURL   string `json:"portalUrl,omitempty"`
	Promo       string `json:"promo,omitempty"`
	PromoURL    string `json:"promoUrl,omitempty"`

	BotURL     string `json:"botUrl,omitempty"`
	MonitorURL string `json:"monitorUrl,omitempty"`
	GuideURL   string `json:"guideUrl,omitempty"`

	LogoFile string `json:"logoFile,omitempty"`

	HwidLimitMessage string `json:"hwidLimitMessage,omitempty"`

	HwidState      string `json:"hwidState,omitempty"`
	HwidMaxDevices int    `json:"hwidMaxDevices,omitempty"`

	RefillDate int64 `json:"refillDate,omitempty"`

	NotifyExpireDays     []int `json:"notifyExpireDays"`
	NotifyTrafficPercent []int `json:"notifyTrafficPercent"`

	ClockSkew   int64 `json:"clockSkew,omitempty"`
	ClockSkewAt int64 `json:"clockSkewAt,omitempty"`

	MigrateURL string `json:"migrateUrl,omitempty"`

	LockMode *bool `json:"lockMode,omitempty"`

	NoServers bool `json:"noServers,omitempty"`

	FallbackURL    string `json:"fallbackUrl,omitempty"`
	FallbackDomain string `json:"fallbackDomain,omitempty"`

	Descriptions map[string]string `json:"descriptions,omitempty"`

	ShowZeroHosts bool `json:"showZeroHosts,omitempty"`

	Groups []Group `json:"groups,omitempty"`
}

type Group struct {
	Name    string   `json:"name"`
	Type    string   `json:"type"`
	Proxies []string `json:"proxies,omitempty"`
}

const panelFileName = "panel.json"

const announceMaxChars = 300

func panelPath(dir string) string {
	return P.Join(dir, panelFileName)
}

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

func ApplyHeaders(info *Info, header map[string][]string, current string) {
	if header == nil {
		return
	}

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

	info.Title = firstNonEmpty(headerValue(header, "profile-title"), info.Title)

	info.HwidState = hwidState(header)
	info.HwidMaxDevices, _ = parseUint(headerValue(header, "x-hwid-max-devices"))
	info.RefillDate = parseRefillDate(headerValue(header, "subscription-refill-date"))

	info.NotifyExpireDays = thresholds(headerValue(header, "notify-expire-days"), 1, 365)
	if info.NotifyExpireDays == nil && boolHeader(header, "notification-subs-expire") {
		info.NotifyExpireDays = append([]int(nil), defaultNotifyExpireDays...)
	}

	info.NotifyTrafficPercent = thresholds(headerValue(header, "notify-traffic-percent"), 1, 100)

	if served := serverTime(header); served > 0 {
		now := time.Now().Unix()

		info.ClockSkew = served - now
		info.ClockSkewAt = now
	}

	info.MigrateURL = firstNonEmpty(
		validateNewURL(current, headerValue(header, "new-url")),
		swapDomain(current, headerValue(header, "new-domain")),
	)

	info.FallbackURL = httpsURL(headerValue(header, "fallback-url"))
	info.FallbackDomain = strings.TrimSpace(headerValue(header, "fallback-domain"))

	info.ShowZeroHosts = boolHeader(header, "clod-show-0hosts")

	info.LockMode = optionalBool(header, "clod-lock-mode")

	if info.LockMode == nil {
		if allowed := optionalBool(header, "global-mode"); allowed != nil {
			locked := !*allowed
			info.LockMode = &locked
		}
	}
}

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

const (
	HwidUnknown      = ""
	HwidActive       = "active"
	HwidLimitReached = "limit"
	HwidNotSupported = "not-supported"
)

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

var defaultNotifyExpireDays = []int{1, 3, 7}

const maxThresholds = 10

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

func swapDomain(current, domain string) string {
	domain = strings.TrimRight(strings.TrimSpace(domain), "/")
	if domain == "" || current == "" {
		return ""
	}

	if _, rest, ok := strings.Cut(domain, "://"); ok {
		domain = rest
	}

	domain, _, _ = strings.Cut(domain, "/")
	if domain == "" {
		return ""
	}

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

func parseRefillDate(raw string) int64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}

	if value, err := strconv.ParseInt(raw, 10, 64); err == nil {
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

const descriptionMaxChars = 60

func Description(raw any) string {
	value, ok := raw.(string)
	if !ok {
		return ""
	}

	return truncate(strings.TrimSpace(value), descriptionMaxChars)
}

func Hidden(raw any) bool {
	switch value := raw.(type) {
	case bool:
		return value
	case string:
		switch strings.ToLower(strings.TrimSpace(value)) {
		case "true", "yes", "on", "1":
			return true
		}

		return false
	case int:
		return value != 0
	case int64:
		return value != 0
	case float64:
		return value != 0
	default:
		return false
	}
}

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
