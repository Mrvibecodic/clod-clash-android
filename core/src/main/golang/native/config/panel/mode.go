package panel

type ModeSource string

const (
	ModeTemplate ModeSource = "template"
	ModeOverride ModeSource = "override"
	ModeChoice   ModeSource = "choice"
	ModeLocked   ModeSource = "locked"
)

func ResolveMode[M comparable](template M, persist, choice *M, locked bool) (M, ModeSource) {
	switch {
	case locked:
		return template, ModeLocked
	case choice != nil:
		return *choice, ModeChoice
	case persist != nil:
		return *persist, ModeOverride
	}

	return template, ModeTemplate
}
