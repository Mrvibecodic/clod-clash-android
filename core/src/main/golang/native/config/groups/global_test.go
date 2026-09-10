package groups

import "testing"

func node(name string) GlobalMember {
	return GlobalMember{Name: name, Resolved: name}
}

func group(name, resolved string) GlobalMember {
	return GlobalMember{Name: name, Resolved: resolved}
}

func TestPreferredGlobalSelection(t *testing.T) {
	cases := []struct {
		name    string
		members []GlobalMember
		want    string
	}{
		{
			"nothing but built-ins",
			[]GlobalMember{node("DIRECT"), node("REJECT")},
			"REJECT",
		},
		{
			"empty",
			nil,
			"REJECT",
		},
		{
			"plain node wins",
			[]GlobalMember{node("DIRECT"), node("REJECT"), node("node-1"), group("Auto", "node-1")},
			"node-1",
		},
		{
			"live group when there is no node",
			[]GlobalMember{node("DIRECT"), node("REJECT"), group("Auto", "node-7")},
			"Auto",
		},
		{
			"group that resolves to REJECT is skipped",
			[]GlobalMember{node("DIRECT"), node("REJECT"), group("Auto", "REJECT")},
			"REJECT",
		},
		{
			"group that resolves to COMPATIBLE is skipped",
			[]GlobalMember{node("DIRECT"), node("REJECT"), group("Auto", "COMPATIBLE")},
			"REJECT",
		},
		{
			"group that resolves to DIRECT is skipped",
			[]GlobalMember{node("DIRECT"), node("REJECT"), group("Bypass", "DIRECT")},
			"REJECT",
		},
		{
			"dead group before a live one",
			[]GlobalMember{
				node("DIRECT"),
				node("REJECT"),
				group("Auto", "REJECT"),
				group("Manual", "node-3"),
			},
			"Manual",
		},
		{
			"unresolved member falls back to its own name",
			[]GlobalMember{node("DIRECT"), node("REJECT"), {Name: "node-9"}},
			"node-9",
		},
		{
			"all built-ins in any order",
			[]GlobalMember{
				node("REJECT"), node("DIRECT"), node("REJECT-DROP"),
				node("PASS"), node("PASS-RULE"), node("COMPATIBLE"),
			},
			"REJECT",
		},
		{
			"name that only starts like a built-in is routable",
			[]GlobalMember{node("DIRECT"), node("REJECT"), node("DIRECT-JP")},
			"DIRECT-JP",
		},
	}

	for _, c := range cases {
		if got := PreferredGlobalSelection(c.members); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

func TestGlobalSelectionRoutable(t *testing.T) {
	members := []GlobalMember{
		node("DIRECT"),
		node("REJECT"),
		node("node-1"),
		group("Auto", "node-7"),
		group("Bypass", "DIRECT"),
	}

	cases := []struct {
		selected string
		want     bool
	}{
		{"", false},
		{"node-1", true},
		{"Auto", true},
		{"DIRECT", false},
		{"REJECT", false},
		{"Bypass", false},
		{"node-that-left-the-profile", false},
	}

	for _, c := range cases {
		if got := GlobalSelectionRoutable(members, c.selected); got != c.want {
			t.Fatalf("selected %q: want %v, got %v", c.selected, c.want, got)
		}
	}
}
