package config

import (
	"sync"

	"github.com/metacubex/mihomo/tunnel"
)

// Серверы «только для мобильной сети» (clod-mobile-only) подписки в ядре: вне
// сети SIM ядро убирает их из групп. Сеть называет служба; пока она её не
// назвала, сеть считается не мобильной.
var (
	mobileMu   sync.Mutex
	onCellular bool
	mobileOnly []string
)

// SetCellular — сеть устройства мобильная (SIM) или нет; changed — набор
// скрытых серверов сменился.
func SetCellular(cellular bool) (changed bool) {
	mobileMu.Lock()
	defer mobileMu.Unlock()

	onCellular = cellular

	return hideMobileOnly()
}

// setMobileOnly — список подписки, которая загружается или загружена в ядро
// (nil — подписки нет).
func setMobileOnly(names []string) {
	mobileMu.Lock()
	defer mobileMu.Unlock()

	mobileOnly = names

	hideMobileOnly()
}

func hideMobileOnly() bool {
	if onCellular {
		return tunnel.SetHidden(nil)
	}

	return tunnel.SetHidden(mobileOnly)
}
