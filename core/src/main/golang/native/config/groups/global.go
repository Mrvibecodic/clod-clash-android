package groups

const GlobalGroupName = "GLOBAL"

var globalNonRoutable = map[string]bool{
	"DIRECT":      true,
	"REJECT":      true,
	"REJECT-DROP": true,
	"PASS":        true,
	"PASS-RULE":   true,
	"COMPATIBLE":  true,
}

type GlobalMember struct {
	Name     string
	Resolved string
}

func routable(member GlobalMember) bool {
	if globalNonRoutable[member.Name] {
		return false
	}

	resolved := member.Resolved
	if resolved == "" {
		resolved = member.Name
	}

	return !globalNonRoutable[resolved]
}

func GlobalSelectionRoutable(members []GlobalMember, selected string) bool {
	if selected == "" {
		return false
	}

	for _, member := range members {
		if member.Name == selected {
			return routable(member)
		}
	}

	return false
}

func PreferredGlobalSelection(members []GlobalMember) string {
	for _, member := range members {
		if !routable(member) {
			continue
		}

		return member.Name
	}

	return "REJECT"
}
