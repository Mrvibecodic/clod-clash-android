package panel

import (
	"os"
	P "path"
)

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

const (
	lockGraceSeconds   int64 = 72 * 60 * 60
	lockGraceIntervals int64 = 3
)

func lockGrace(info Info) int64 {
	grace := lockGraceSeconds
	if info.UpdateInterval > grace/lockGraceIntervals {
		grace = info.UpdateInterval * lockGraceIntervals
	}

	return grace
}

func LockUntil(info Info) int64 {
	if info.LockMode == nil || !*info.LockMode || info.LockPermanent || info.UpdatedAt <= 0 {
		return 0
	}

	return info.UpdatedAt + lockGrace(info)
}

func LockActive(info Info, now int64) bool {
	if info.LockMode == nil || !*info.LockMode {
		return false
	}

	until := LockUntil(info)

	return until == 0 || now <= until
}

func WithUpdatedAt(dir string, info Info) Info {
	if info.UpdatedAt > 0 {
		return info
	}

	if stat, err := os.Stat(P.Join(dir, "config.yaml")); err == nil {
		info.UpdatedAt = stat.ModTime().Unix()
	}

	return info
}

func MarkUpdated(dir string, now, interval int64) {
	info := Read(dir)

	info.UpdatedAt = now
	info.UpdateInterval = interval

	Write(dir, info)
}

func ReadWithMode(dir string, subscription func() string) Info {
	info := Read(dir)

	if info.Mode == "" {
		info.Mode = templateMode(subscription())

		Write(dir, info)
	}

	return info
}
