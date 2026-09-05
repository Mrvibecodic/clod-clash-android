package groups

import "strings"

// RejectWhenProvidersAreEmpty pins empty-fallback: REJECT on every group that is
// fed only by remote providers and has no fallback of its own. Without it the
// core substitutes COMPATIBLE, a direct adapter, so a group whose providers were
// not downloaded yet sends the whole group out unprotected and without a word.
// Groups with inline nodes, with a local or inline provider, or with a fallback
// from the subscription are left to the core.
func RejectWhenProvidersAreEmpty(groups []map[string]any, providers map[string]map[string]any) []string {
	var patched []string

	for _, group := range groups {
		if group == nil {
			continue
		}

		if _, pinned := group["empty-fallback"]; pinned {
			continue
		}

		if len(Strings(group["proxies"])) > 0 {
			continue
		}

		uses := Strings(group["use"])
		if len(uses) == 0 {
			continue
		}

		remoteOnly := true

		for _, use := range uses {
			provider, known := providers[use]
			if !known {
				remoteOnly = false

				break
			}

			url, ok := provider["url"].(string)
			if !ok || strings.TrimSpace(url) == "" {
				remoteOnly = false

				break
			}
		}

		if !remoteOnly {
			continue
		}

		group["empty-fallback"] = "REJECT"

		if name, ok := group["name"].(string); ok {
			patched = append(patched, name)
		}
	}

	return patched
}

// Strings reads a YAML list of names that decoding left as []any.
func Strings(value any) []string {
	switch v := value.(type) {
	case []string:
		return v
	case []any:
		out := make([]string, 0, len(v))

		for _, item := range v {
			if s, ok := item.(string); ok {
				out = append(out, s)
			}
		}

		return out
	}

	return nil
}
