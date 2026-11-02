package config

import (
	"encoding/json"
	"errors"
	"net/netip"
	"time"

	"cfa/native/common"
	"cfa/native/config/groups"
	"cfa/native/config/overlay"
	"cfa/native/config/panel"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

var processors = []processor{
	patchExternalController,
	patchOverride,
	patchGeneral,
	patchProfile,
	patchDns,
	patchTun,
	patchListeners,
	patchProviders,
	patchEmptyFallback,
	validConfig,
}

// info — panel.json папки подписки: читается один раз на разбор, а при
// проверке загрузки приходит из памяти, вместе с только что принятыми заголовками.
// save — разбор для загрузки или проверки: только он пишет tun.json и
// inbound.json; разбор ради чтения узлов папку не трогает — она может быть уже подменена.
type parseInput struct {
	dir  string
	info panel.Info
	save bool
}

type processor func(cfg *config.RawConfig, src parseInput) error

func overrideMode(content string) *tunnel.TunnelMode {
	var slot struct {
		Mode *tunnel.TunnelMode `json:"mode"`
	}

	if err := json.Unmarshal([]byte(content), &slot); err != nil {
		return nil
	}

	return slot.Mode
}

func modeLocked(profileDir string, info panel.Info) bool {
	return panel.LockActive(panel.WithUpdatedAt(profileDir, info), time.Now().Unix())
}

func patchOverride(cfg *config.RawConfig, src parseInput) error {
	template := cfg.Mode

	persist := ReadOverride(OverrideSlotPersist)
	session := ReadOverride(OverrideSlotSession)

	overlay.Apply(cfg, persist, session, log.Warnln)

	mode, source := panel.ResolveMode(
		template.String(),
		modeName(overrideMode(persist)),
		modeName(overrideMode(session)),
		modeLocked(src.dir, src.info),
	)

	// The provider pinned the mode: the override slots must not win over the subscription.
	if source == panel.ModeLocked && cfg.Mode.String() != mode {
		log.Warnln("Ignore override mode %s: the mode is locked by the subscription", cfg.Mode.String())
	}

	cfg.Mode = tunnel.ModeMapping[mode]

	return nil
}

func patchExternalController(cfg *config.RawConfig, _ parseInput) error {
	cfg.ExternalController = ""
	cfg.ExternalControllerTLS = ""
	cfg.ExternalControllerUnix = ""
	cfg.ExternalControllerPipe = ""

	return nil
}

// Своя папка стандартной панели: панель провайдера, скачанная раньше в ui,
// на её место не встанет
const defaultExternalUIName = "metacubexd"

func patchGeneral(cfg *config.RawConfig, src parseInput) error {
	cfg.Interface = ""
	cfg.RoutingMark = 0

	overlay.DefaultMixedPort(cfg, ReadOverride(OverrideSlotPersist), ReadOverride(OverrideSlotSession))

	if src.save {
		panel.WriteInboundPrefs(src.dir, panel.InboundPrefs{
			MixedPort: cfg.MixedPort,
			HttpPort:  cfg.Port,
		})
	}

	if cfg.ExternalController != "" || cfg.ExternalControllerTLS != "" {
		// Панель управления — всегда стандартная: код панели по адресу из подписки
		// получил бы доступ к управлению ядром
		cfg.ExternalUI = src.dir + "/ui"
		cfg.ExternalUIURL = config.DefaultRawConfig().ExternalUIURL
		cfg.ExternalUIName = defaultExternalUIName
	} else {
		// Without a controller the dashboard is unreachable; do not let the core download it.
		cfg.ExternalUI = ""
		cfg.ExternalUIURL = ""
		cfg.ExternalUIName = ""
	}

	return nil
}

func patchProfile(cfg *config.RawConfig, _ parseInput) error {
	cfg.Profile.StoreSelected = false
	cfg.Profile.StoreFakeIP = true

	return nil
}

func patchDns(cfg *config.RawConfig, _ parseInput) error {
	overlay.Dns(cfg, log.Warnln, ReadOverride(OverrideSlotPersist), ReadOverride(OverrideSlotSession))

	warnPrivateFakeIPRange(cfg.DNS.FakeIPRange)

	return nil
}

var privateNets = []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "0.0.0.0/8", "224.0.0.0/4"}

func warnPrivateFakeIPRange(fakeIPRange string) {
	if fakeIPRange == "" {
		return
	}

	rangePrefix, err := netip.ParsePrefix(fakeIPRange)
	if err != nil {
		return
	}

	for _, n := range privateNets {
		private := netip.MustParsePrefix(n)

		if private.Overlaps(rangePrefix) {
			log.Warnln("[APP] fake-ip-range %s overlaps private network %s: with bypass of private networks enabled these addresses are routed outside the tunnel", fakeIPRange, n)

			return
		}
	}
}

func patchTun(cfg *config.RawConfig, src parseInput) error {
	prefs := panel.TunPrefsOf(cfg.Tun.IncludePackage, cfg.Tun.ExcludePackage, src.info)

	prefs.Stack = panel.TemplateStack(cfg.Tun.Enable, cfg.Tun.Stack.String(), cfg.Listeners)

	for _, mapping := range cfg.Listeners {
		if listenerType, ok := mapping["type"].(string); !ok || listenerType != "tun" {
			continue
		}

		prefs.IncludePackages = panel.MergePackages(prefs.IncludePackages, groups.Strings(mapping["include-package"]))
		prefs.ExcludePackages = panel.MergePackages(prefs.ExcludePackages, groups.Strings(mapping["exclude-package"]))
	}

	if src.save {
		panel.WriteTunPrefs(src.dir, prefs)
	}

	cfg.Tun.Enable = false
	cfg.Tun.AutoRoute = false
	cfg.Tun.AutoDetectInterface = false
	return nil
}

func patchListeners(cfg *config.RawConfig, _ parseInput) error {
	newListeners := make([]map[string]any, 0, len(cfg.Listeners))
	for _, mapping := range cfg.Listeners {
		if proxyType, existType := mapping["type"].(string); existType {
			switch proxyType {
			case "tproxy", "redir", "tun":
				continue
			}
		}
		newListeners = append(newListeners, mapping)
	}
	cfg.Listeners = newListeners
	return nil
}

// A group fed only by remote providers falls back to COMPATIBLE when nothing
// was downloaded yet, and COMPATIBLE is a direct adapter: the whole group would
// go out unprotected without a word. Where the subscription did not pick its own
// fallback, reject instead.
func patchEmptyFallback(cfg *config.RawConfig, _ parseInput) error {
	for _, name := range groups.RejectWhenProvidersAreEmpty(cfg.ProxyGroup, cfg.ProxyProvider) {
		log.Infoln("[APP] Group %s rejects while its providers are empty", name)
	}

	return nil
}

func patchProviders(cfg *config.RawConfig, src parseInput) error {
	forEachProviders(cfg, func(index int, total int, key string, provider map[string]any, prefix string) {
		path, _ := provider["path"].(string)
		if len(path) > 0 {
			path = common.ResolveAsRoot(path)
		} else if url, ok := provider["url"].(string); ok {
			path = prefix + "/" + utils.MakeHash([]byte(url)).String()
		} else {
			return
		}
		provider["path"] = src.dir + "/providers/" + path
	})

	return nil
}

func validConfig(cfg *config.RawConfig, src parseInput) error {
	if len(cfg.Proxy) == 0 && len(cfg.ProxyProvider) == 0 && !src.info.RefusesDevice() {
		return errors.New("profile does not contain `proxies` or `proxy-providers`")
	}

	return nil
}

func process(cfg *config.RawConfig, src parseInput) error {
	for _, p := range processors {
		if err := p(cfg, src); err != nil {
			return err
		}
	}

	return nil
}
