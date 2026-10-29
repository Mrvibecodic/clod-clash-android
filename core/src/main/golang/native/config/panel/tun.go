package panel

import (
	"os"
	P "path"
	"strings"
)

type TunPrefs struct {
	Stack           string   `json:"stack,omitempty"`
	IncludePackages []string `json:"includePackages,omitempty"`
	ExcludePackages []string `json:"excludePackages,omitempty"`
	AllApps         bool     `json:"allApps,omitempty"`
}

const tunFileName = "tun.json"

func tunPath(dir string) string {
	return P.Join(dir, tunFileName)
}

func (t TunPrefs) IsEmpty() bool {
	return t.Stack == "" && len(t.IncludePackages) == 0 && len(t.ExcludePackages) == 0 && !t.AllApps
}

func NormalizeTunStack(stack string) string {
	switch strings.ToLower(strings.TrimSpace(stack)) {
	case "system":
		return "system"
	case "gvisor":
		return "gvisor"
	case "mixed":
		return "mixed"
	case "mips":
		return "mips"
	}

	return ""
}

func SanitizePackages(packages []string) []string {
	if len(packages) == 0 {
		return nil
	}

	seen := make(map[string]struct{}, len(packages))
	result := make([]string, 0, len(packages))

	for _, name := range packages {
		name = strings.TrimSpace(name)
		if name == "" {
			continue
		}

		if _, ok := seen[name]; ok {
			continue
		}

		seen[name] = struct{}{}
		result = append(result, name)
	}

	if len(result) == 0 {
		return nil
	}

	return result
}

func MergePackages(base []string, extra []string) []string {
	return SanitizePackages(append(append([]string(nil), base...), extra...))
}

// TunPrefsOf — списки приложений из секции tun конфига и режим провайдера из
// заголовков той же подписки.
func TunPrefsOf(include, exclude []string, info Info) TunPrefs {
	return TunPrefs{
		IncludePackages: SanitizePackages(include),
		ExcludePackages: SanitizePackages(exclude),
		AllApps:         info.AllApps,
	}
}

func WriteTunPrefs(dir string, prefs TunPrefs) {
	if prefs.IsEmpty() {
		_ = os.Remove(tunPath(dir))

		return
	}

	writeJSON(tunPath(dir), &prefs)
}

func StringsFromAny(value any) []string {
	items, ok := value.([]any)
	if !ok {
		return nil
	}

	result := make([]string, 0, len(items))

	for _, item := range items {
		if s, ok := item.(string); ok {
			result = append(result, s)
		}
	}

	return result
}
