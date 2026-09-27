package config

import (
	"net/netip"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

var (
	defaultNameServers = []string{
		"1.0.0.1",
		"8.8.4.4",
		"9.9.9.10",
	}
	defaultFakeIPFilter = []string{
		"+.stun.*.*",
		"+.stun.*.*.*",
		"+.stun.*.*.*.*",
		"+.stun.*.*.*.*.*",

		"lens.l.google.com",

		"*.n.n.srv.nintendo.net",

		"+.stun.playstation.net",

		"xbox.*.*.microsoft.com",
		"*.*.xboxlive.com",

		"*.msftncsi.com",
		"*.msftconnecttest.com",

		"WORKGROUP",
	}
	defaultFakeIPRange = "198.18.0.1/16"
)

// Первый адрес прежнего заводского диапазона 28.0.0.0/8. Ядро сбрасывает кэш
// fake-ip само, только найдя в нём первый адрес ТЕКУЩЕГО диапазона, а хосту из
// кэша отдаёт его прежний адрес, не глядя на диапазон: без сброса хосты прошлой
// сессии ещё сессию получали бы адреса 28.x, на которых не работают UDP и
// правила по IP. Сбрасываем, как само ядро в restoreState, на новом пуле до
// применения конфига, пока он никому не отвечает. После сброса старый адрес
// больше не выдаётся — проверка срабатывает один раз.
var legacyFakeIPFirst = netip.MustParseAddr("28.0.0.4")

func forgetLegacyFakeIPs(dns *config.DNS) {
	pool := dns.FakeIPPool

	if pool == nil || pool.IPNet().Contains(legacyFakeIPFirst) || !pool.Exist(legacyFakeIPFirst) {
		return
	}

	if err := pool.FlushFakeIP(); err != nil {
		log.Warnln("Flush fake-ip cache of the former default range: %s", err)

		return
	}

	log.Infoln("Fake-ip cache of the former default range flushed")
}
