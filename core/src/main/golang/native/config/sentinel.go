package config

import (
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/config"
)

const nilUUID = "00000000-0000-0000-0000-000000000000"

var serverlessTypes = map[string]bool{
	"direct": true, "reject": true, "reject-drop": true, "pass": true, "dns": true,
}

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

type SentinelReport struct {
	Remarks       []string
	OnlySentinels bool
}

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

func filterSentinels(cfg *config.RawConfig, profileDir string) error {
	if len(cfg.Proxy) == 0 {
		return nil
	}

	if profileShowsZeroHosts(profileDir) {
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

		if len(filtered) == 0 {
			filtered = append(filtered, "DIRECT")
		}

		group["proxies"] = filtered
	}

	return nil
}

func profileShowsZeroHosts(profileDir string) bool {
	if profileDir == "" {
		return false
	}

	return readPanelInfo(profileDir).ShowZeroHosts
}
