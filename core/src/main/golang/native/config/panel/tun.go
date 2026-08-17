package panel

import (
	"encoding/json"
	"os"
	P "path"
	"strings"
)

type TunPrefs struct {
	Stack           string   `json:"stack,omitempty"`
	IncludePackages []string `json:"includePackages,omitempty"`
	ExcludePackages []string `json:"excludePackages,omitempty"`
}

const tunFileName = "tun.json"

func tunPath(dir string) string {
	return P.Join(dir, tunFileName)
}

func (t TunPrefs) IsEmpty() bool {
	return t.Stack == "" && len(t.IncludePackages) == 0 && len(t.ExcludePackages) == 0
}

func NormalizeTunStack(stack string) string {
	switch strings.ToLower(strings.TrimSpace(stack)) {
	case "system":
		return "system"
	case "gvisor":
		return "gvisor"
	case "mixed":
		return "mixed"
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

func ReadTunPrefs(dir string) TunPrefs {
	var prefs TunPrefs

	bytes, err := os.ReadFile(tunPath(dir))
	if err != nil {
		return prefs
	}

	_ = json.Unmarshal(bytes, &prefs)

	prefs.Stack = NormalizeTunStack(prefs.Stack)
	prefs.IncludePackages = SanitizePackages(prefs.IncludePackages)
	prefs.ExcludePackages = SanitizePackages(prefs.ExcludePackages)

	return prefs
}

func WriteTunPrefs(dir string, prefs TunPrefs) {
	if prefs.IsEmpty() {
		_ = os.Remove(tunPath(dir))

		return
	}

	bytes, err := json.Marshal(&prefs)
	if err != nil {
		return
	}

	tmp := tunPath(dir) + ".tmp"

	if err := os.WriteFile(tmp, bytes, 0o644); err != nil {
		return
	}

	if err := os.Rename(tmp, tunPath(dir)); err != nil {
		_ = os.Remove(tmp)
	}
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
