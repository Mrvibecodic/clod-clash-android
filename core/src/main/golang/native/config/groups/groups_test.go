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

func TestMainIsTheMatchTargetGroup(t *testing.T) {
	cases := []struct {
		names  []string
		target string
		want   string
	}{
		{[]string{"A", "B"}, "B", "B"},
		{[]string{"A", "B"}, "DIRECT", "A"},
		{[]string{"A", "B"}, "", "A"},
		{[]string{"GLOBAL"}, "Proxy", "GLOBAL"},
		{nil, "B", ""},
		{[]string{}, "", ""},
	}

	for _, c := range cases {
		if got := Main(c.names, c.target); got != c.want {
			t.Fatalf("Main(%v, %q) = %q, want %q", c.names, c.target, got, c.want)
		}
	}
}

func TestMainOfPrefersSelectableGroups(t *testing.T) {
	cases := []struct {
		list   []Candidate
		target string
		want   string
	}{
		{[]Candidate{{"Balance", false}, {"Proxy", true}}, "Balance", "Proxy"},
		{[]Candidate{{"Balance", false}, {"Proxy", true}}, "", "Proxy"},
		{[]Candidate{{"Proxy", true}, {"Auto", true}}, "Auto", "Auto"},
		{[]Candidate{{"Balance", false}, {"Chain", false}}, "Chain", "Chain"},
		{[]Candidate{{"Balance", false}, {"Chain", false}}, "", "Balance"},
		{nil, "Proxy", ""},
	}

	for _, c := range cases {
		if got := MainOf(c.list, c.target); got != c.want {
			t.Fatalf("MainOf(%v, %q) = %q, want %q", c.list, c.target, got, c.want)
		}
	}
}

func TestSelectableTypeMatchesTheCore(t *testing.T) {
	for _, kind := range []string{"select", "url-test", "fallback"} {
		if !SelectableType(kind) {
			t.Fatalf("%s должна считаться группой с выбором", kind)
		}
	}

	for _, kind := range []string{"load-balance", "relay", "", "Select"} {
		if SelectableType(kind) {
			t.Fatalf("%q не должна считаться группой с выбором", kind)
		}
	}
}

func TestMatchTargetTakesTheFirstMatchRule(t *testing.T) {
	cases := []struct {
		rules []string
		want  string
	}{
		{[]string{"DOMAIN-SUFFIX,youtube.com,YouTube", "MATCH,Proxy"}, "Proxy"},
		{[]string{" match , Proxy "}, "Proxy"},
		{[]string{"MATCH,Proxy", "MATCH,Other"}, "Proxy"},
		{[]string{"DOMAIN,match.example,Ads", "GEOIP,RU,DIRECT"}, ""},
		{[]string{"MATCH"}, ""},
		{nil, ""},
	}

	for _, c := range cases {
		if got := MatchTarget(c.rules); got != c.want {
			t.Fatalf("MatchTarget(%v) = %q, want %q", c.rules, got, c.want)
		}
	}
}
