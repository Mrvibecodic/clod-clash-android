package panel

type ModeSource string

const (
	ModeTemplate ModeSource = "template"
	ModeOverride ModeSource = "override"
	ModeChoice   ModeSource = "choice"
	ModeLocked   ModeSource = "locked"
)

const DefaultMode = "rule"

func templateMode(mode string) string {
	switch mode {
	case "rule", "global", "direct":
		return mode
	}

	return DefaultMode
}

func ResolveMode(template string, persist, choice *string, locked bool) (string, ModeSource) {
	template = templateMode(template)

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

func ReadWithMode(dir string, subscription func() string) Info {
	info := Read(dir)

	if info.Mode == "" {
		info.Mode = templateMode(subscription())

		Write(dir, info)
	}

	return info
}
