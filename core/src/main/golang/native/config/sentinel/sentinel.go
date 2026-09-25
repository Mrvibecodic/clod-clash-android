package sentinel

import (
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/common/yaml"
)

const nilUUID = "00000000-0000-0000-0000-000000000000"

var serverlessTypes = map[string]bool{
	"direct": true, "reject": true, "reject-drop": true, "pass": true, "dns": true,
}

var credentialKeys = []string{"uuid", "password", "psk", "private-key", "auth", "auth-str", "token"}

func serverless(proxy map[string]any) bool {
	kind, ok := proxy["type"].(string)

	return ok && serverlessTypes[strings.ToLower(strings.TrimSpace(kind))]
}

func Is(proxy map[string]any) bool {
	if serverless(proxy) {
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

type Report struct {
	Remarks       []string
	Names         []string
	OnlySentinels bool
}

const maxReportedRemarks = 4

func Inspect(proxies []map[string]any) Report {
	report := Report{}

	if len(proxies) == 0 {
		return report
	}

	real := 0

	for _, proxy := range proxies {
		// Built-in outbounds have no server: they are neither placeholders nor real nodes.
		if serverless(proxy) {
			continue
		}

		if !Is(proxy) {
			real++

			continue
		}

		name, ok := proxy["name"].(string)
		if !ok || name == "" {
			continue
		}

		report.Names = append(report.Names, name)

		if len(report.Remarks) < maxReportedRemarks {
			report.Remarks = append(report.Remarks, name)
		}
	}

	report.OnlySentinels = real == 0 && len(report.Names) > 0

	return report
}

const RefusedConfig = "proxies: []\nrules:\n  - MATCH,REJECT\n"

func Refused(previous []byte) []byte {
	if disarmed, ok := Disarm(previous); ok {
		return disarmed
	}

	return []byte(RefusedConfig)
}

func Disarm(config []byte) ([]byte, bool) {
	var document map[string]any
	if err := yaml.Unmarshal(config, &document); err != nil || document == nil {
		return nil, false
	}

	proxies, _ := document["proxies"].([]any)

	placeholders := make([]any, 0, len(proxies))
	for _, proxy := range proxies {
		fields, ok := proxy.(map[string]any)
		if !ok {
			continue
		}

		name := fields["name"]
		switch value := name.(type) {
		case string:
			if value == "" {
				continue
			}
		case int, int64, uint64, float64, bool:
		default:
			continue
		}

		placeholders = append(placeholders, map[string]any{
			"name":    name,
			"type":    "vless",
			"server":  "0.0.0.0",
			"port":    1,
			"uuid":    nilUUID,
			"network": "tcp",
			"udp":     true,
		})
	}

	if len(placeholders) == 0 {
		return nil, false
	}

	document["proxies"] = placeholders

	delete(document, "proxy-providers")

	groups, _ := document["proxy-groups"].([]any)
	for _, group := range groups {
		fields, ok := group.(map[string]any)
		if !ok {
			continue
		}

		delete(fields, "use")
		delete(fields, "include-all-providers")

		if includeAll, _ := fields["include-all"].(bool); includeAll {
			fields["include-all-proxies"] = true
		}
		delete(fields, "include-all")

		members, _ := fields["proxies"].([]any)
		if includeProxies, _ := fields["include-all-proxies"].(bool); len(members) == 0 && !includeProxies {
			fields["proxies"] = []any{"REJECT"}
		}

		if _, present := fields["empty-fallback"]; !present {
			fields["empty-fallback"] = "REJECT"
		}
	}

	disarmed, err := yaml.Marshal(document)
	if err != nil {
		return nil, false
	}

	return disarmed, true
}
