package config

import (
	"encoding/json"
	"errors"
	"net/netip"
	"slices"
	"strings"

	"github.com/dlclark/regexp2"

	"cfa/native/common"
	"cfa/native/config/groups"
	"cfa/native/config/panel"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
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

type processor func(cfg *config.RawConfig, profileDir string) error

func patchOverride(cfg *config.RawConfig, profileDir string) error {
	mode := cfg.Mode
	nameServers := cfg.DNS.NameServer

	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotPersist))).Decode(cfg); err != nil {
		log.Warnln("Apply persist override: %s", err.Error())
	}
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotSession))).Decode(cfg); err != nil {
		log.Warnln("Apply session override: %s", err.Error())
	}

	// The provider pinned the mode: the override slots must not win over the subscription.
	if locked := panel.Read(profileDir).LockMode; locked != nil && *locked && cfg.Mode != mode {
		log.Warnln("Ignore override mode %s: the mode is locked by the subscription", cfg.Mode.String())

		cfg.Mode = mode
	}

	// An empty list from the override would make the core reject the profile.
	if len(cfg.DNS.NameServer) == 0 && len(nameServers) > 0 {
		log.Warnln("Override left dns.nameserver empty: keeping the subscription list")

		cfg.DNS.NameServer = nameServers
	}

	return nil
}

func patchExternalController(cfg *config.RawConfig, _ string) error {
	cfg.ExternalController = ""
	cfg.ExternalControllerTLS = ""
	cfg.ExternalControllerUnix = ""
	cfg.ExternalControllerPipe = ""

	return nil
}

const defaultMixedPort = 7890

const mixedPortKey = "mixed-port"

func portOccupied(cfg *config.RawConfig, port int) bool {
	return cfg.Port == port ||
		cfg.SocksPort == port ||
		cfg.RedirPort == port ||
		cfg.TProxyPort == port
}

func mixedPortOverridden(slot OverrideSlot) bool {
	var keys map[string]json.RawMessage

	if err := json.Unmarshal([]byte(ReadOverride(slot)), &keys); err != nil {
		return false
	}

	_, ok := keys[mixedPortKey]

	return ok
}

func patchGeneral(cfg *config.RawConfig, profileDir string) error {
	cfg.Interface = ""
	cfg.RoutingMark = 0

	if cfg.MixedPort == 0 && cfg.Port == 0 && !cfg.AllowLan &&
		!portOccupied(cfg, defaultMixedPort) &&
		!mixedPortOverridden(OverrideSlotPersist) &&
		!mixedPortOverridden(OverrideSlotSession) {
		cfg.MixedPort = defaultMixedPort
	}

	panel.WriteInboundPrefs(profileDir, panel.InboundPrefs{
		MixedPort: cfg.MixedPort,
		HttpPort:  cfg.Port,
	})

	if cfg.ExternalController != "" || cfg.ExternalControllerTLS != "" {
		cfg.ExternalUI = profileDir + "/ui"
	} else {
		// Without a controller the dashboard is unreachable; do not let the core download it.
		cfg.ExternalUI = ""
		cfg.ExternalUIURL = ""
		cfg.ExternalUIName = ""
	}

	return nil
}

func patchProfile(cfg *config.RawConfig, _ string) error {
	cfg.Profile.StoreSelected = false
	cfg.Profile.StoreFakeIP = true

	return nil
}

func patchDns(cfg *config.RawConfig, _ string) error {
	if !cfg.DNS.Enable {
		cfg.DNS = config.DefaultRawConfig().DNS
		cfg.DNS.NameServer = defaultNameServers
		cfg.DNS.EnhancedMode = C.DNSFakeIP
		cfg.DNS.FakeIPRange = defaultFakeIPRange
		cfg.DNS.FakeIPFilter = defaultFakeIPFilter

		cfg.ClashForAndroid.AppendSystemDNS = true

		for _, slot := range []OverrideSlot{OverrideSlotPersist, OverrideSlotSession} {
			applyOwnDns(cfg, ReadOverride(slot))
		}

		if len(cfg.DNS.NameServer) == 0 {
			log.Warnln("Override left dns.nameserver empty: using the built-in list")

			cfg.DNS.NameServer = defaultNameServers
		}

		cfg.DNS.Enable = true
	}

	if cfg.ClashForAndroid.AppendSystemDNS && !slices.ContainsFunc(cfg.DNS.NameServer, isSystemNameServer) {
		cfg.DNS.NameServer = append(cfg.DNS.NameServer, systemNameServer)
	}

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

const systemNameServer = "system://"

func isSystemNameServer(server string) bool {
	return server == systemNameServer || server == "system"
}

func applyOwnDns(cfg *config.RawConfig, override string) {
	var keys struct {
		DNS json.RawMessage `json:"dns"`
		App json.RawMessage `json:"clash-for-android"`
	}

	if err := json.Unmarshal([]byte(override), &keys); err != nil {
		return
	}

	var mode struct {
		Enable *bool `json:"enable"`
	}

	if len(keys.DNS) > 0 {
		if err := json.Unmarshal(keys.DNS, &mode); err != nil || mode.Enable != nil {
			return
		}
	}

	if len(keys.App) > 0 {
		if err := json.Unmarshal(keys.App, &cfg.ClashForAndroid); err != nil {
			log.Warnln("Apply own app override: %s", err.Error())
		}
	}

	if len(keys.DNS) > 0 {
		if err := json.Unmarshal(keys.DNS, &cfg.DNS); err != nil {
			log.Warnln("Apply own dns override: %s", err.Error())
		}
	}
}

func patchTun(cfg *config.RawConfig, profileDir string) error {
	prefs := panel.TunPrefs{
		IncludePackages: panel.SanitizePackages(cfg.Tun.IncludePackage),
		ExcludePackages: panel.SanitizePackages(cfg.Tun.ExcludePackage),
	}

	if cfg.Tun.Enable {
		prefs.Stack = panel.NormalizeTunStack(cfg.Tun.Stack.String())
	}

	for _, mapping := range cfg.Listeners {
		if listenerType, ok := mapping["type"].(string); !ok || listenerType != "tun" {
			continue
		}

		prefs.IncludePackages = panel.MergePackages(prefs.IncludePackages, panel.StringsFromAny(mapping["include-package"]))
		prefs.ExcludePackages = panel.MergePackages(prefs.ExcludePackages, panel.StringsFromAny(mapping["exclude-package"]))

		if prefs.Stack == "" {
			if stack, ok := mapping["stack"].(string); ok {
				prefs.Stack = panel.NormalizeTunStack(stack)
			}
		}
	}

	panel.WriteTunPrefs(profileDir, prefs)

	cfg.Tun.Enable = false
	cfg.Tun.AutoRoute = false
	cfg.Tun.AutoDetectInterface = false
	return nil
}

func patchListeners(cfg *config.RawConfig, _ string) error {
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
func patchEmptyFallback(cfg *config.RawConfig, _ string) error {
	for _, name := range groups.RejectWhenProvidersAreEmpty(cfg.ProxyGroup, cfg.ProxyProvider) {
		log.Infoln("[APP] Group %s rejects while its providers are empty", name)
	}

	return nil
}

func patchProviders(cfg *config.RawConfig, profileDir string) error {
	forEachProviders(cfg, func(index int, total int, key string, provider map[string]any, prefix string) {
		path, _ := provider["path"].(string)
		if len(path) > 0 {
			path = common.ResolveAsRoot(path)
		} else if url, ok := provider["url"].(string); ok {
			path = prefix + "/" + utils.MakeHash([]byte(url)).String()
		} else {
			return
		}
		provider["path"] = profileDir + "/providers/" + path
	})

	return nil
}

func validConfig(cfg *config.RawConfig, _ string) error {
	if len(cfg.Proxy) == 0 && len(cfg.ProxyProvider) == 0 {
		return errors.New("profile does not contain `proxies` or `proxy-providers`")
	}

	if _, err := regexp2.Compile(cfg.ClashForAndroid.UiSubtitlePattern, 0); err != nil {
		log.Warnln("Ignore unsupported ui-subtitle-pattern: %s", err.Error())

		cfg.ClashForAndroid.UiSubtitlePattern = ""
	}

	return nil
}

func process(cfg *config.RawConfig, profileDir string) error {
	for _, p := range processors {
		if err := p(cfg, profileDir); err != nil {
			return err
		}
	}

	return nil
}
