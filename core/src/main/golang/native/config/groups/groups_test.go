package groups

import "testing"

func remoteProviders() map[string]map[string]any {
	return map[string]map[string]any{
		"remote": {"type": "http", "url": "https://example.com/list"},
		"inline": {"type": "inline", "payload": []any{}},
		"local":  {"type": "file", "path": "./list.yaml"},
	}
}

func TestRemoteOnlyGroupRejectsWhenEmpty(t *testing.T) {
	groups := []map[string]any{
		{"name": "Auto", "type": "url-test", "use": []any{"remote"}},
	}

	patched := RejectWhenProvidersAreEmpty(groups, remoteProviders())

	if got := groups[0]["empty-fallback"]; got != "REJECT" {
		t.Fatalf("empty-fallback = %v, want REJECT", got)
	}

	if len(patched) != 1 || patched[0] != "Auto" {
		t.Fatalf("patched = %v, want [Auto]", patched)
	}
}

func TestGroupWithInlineProxiesKeepsCoreDefault(t *testing.T) {
	groups := []map[string]any{
		{"name": "Mixed", "type": "select", "use": []any{"remote"}, "proxies": []any{"DIRECT"}},
	}

	RejectWhenProvidersAreEmpty(groups, remoteProviders())

	if _, patched := groups[0]["empty-fallback"]; patched {
		t.Fatal("a group with inline nodes must keep the core default")
	}
}

func TestInlineOrLocalProviderKeepsCoreDefault(t *testing.T) {
	groups := []map[string]any{
		{"name": "Local", "type": "select", "use": []any{"inline"}},
		{"name": "File", "type": "select", "use": []any{"local"}},
		{"name": "Mixed", "type": "select", "use": []any{"remote", "local"}},
	}

	RejectWhenProvidersAreEmpty(groups, remoteProviders())

	for _, group := range groups {
		if _, patched := group["empty-fallback"]; patched {
			t.Fatalf("group %v must keep the core default", group["name"])
		}
	}
}

func TestUnknownProviderKeepsCoreDefault(t *testing.T) {
	groups := []map[string]any{
		{"name": "Ghost", "type": "select", "use": []any{"missing"}},
	}

	RejectWhenProvidersAreEmpty(groups, remoteProviders())

	if _, patched := groups[0]["empty-fallback"]; patched {
		t.Fatal("an unknown provider is not proof that the group is remote only")
	}
}

func TestSubscriptionFallbackWins(t *testing.T) {
	groups := []map[string]any{
		{"name": "Auto", "type": "url-test", "use": []any{"remote"}, "empty-fallback": "DIRECT"},
	}

	RejectWhenProvidersAreEmpty(groups, remoteProviders())

	if got := groups[0]["empty-fallback"]; got != "DIRECT" {
		t.Fatalf("empty-fallback = %v, want the value from the subscription", got)
	}
}

func TestStringsReadsBothShapes(t *testing.T) {
	if got := Strings([]any{"a", 1, "b"}); len(got) != 2 || got[0] != "a" || got[1] != "b" {
		t.Fatalf("Strings([]any) = %v", got)
	}

	if got := Strings([]string{"a"}); len(got) != 1 {
		t.Fatalf("Strings([]string) = %v", got)
	}

	if got := Strings("a"); got != nil {
		t.Fatalf("Strings(string) = %v, want nil", got)
	}
}
