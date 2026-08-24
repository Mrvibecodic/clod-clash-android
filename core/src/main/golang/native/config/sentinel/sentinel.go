package sentinel

import (
	"strconv"
	"strings"
)

const nilUUID = "00000000-0000-0000-0000-000000000000"

var serverlessTypes = map[string]bool{
	"direct": true, "reject": true, "reject-drop": true, "pass": true, "dns": true,
}

var credentialKeys = []string{"uuid", "password", "psk", "private-key", "auth", "auth-str", "token"}

func Is(proxy map[string]any) bool {
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

	report.OnlySentinels = real == 0

	return report
}
