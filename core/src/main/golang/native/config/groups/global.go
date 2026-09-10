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

func PreferredGlobalSelection(members []GlobalMember) string {
	for _, member := range members {
		if globalNonRoutable[member.Name] {
			continue
		}

		resolved := member.Resolved
		if resolved == "" {
			resolved = member.Name
		}

		if globalNonRoutable[resolved] {
			continue
		}

		return member.Name
	}

	return "REJECT"
}
