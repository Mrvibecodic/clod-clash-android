package overlay

import (
	"encoding/json"
	"slices"
	"strings"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

const defaultMixedPort = 7890

const mixedPortKey = "mixed-port"

const systemNameServer = "system://"

type Warn func(format string, args ...any)

func Apply(cfg *config.RawConfig, persist, session string, warn Warn) {
	nameServers := cfg.DNS.NameServer

	if err := json.NewDecoder(strings.NewReader(persist)).Decode(cfg); err != nil {
		warn("Apply persist override: %s", err.Error())
	}
	if err := json.NewDecoder(strings.NewReader(session)).Decode(cfg); err != nil {
		warn("Apply session override: %s", err.Error())
	}

	// An empty list from the override would make the core reject the profile.
	if len(cfg.DNS.NameServer) == 0 && len(nameServers) > 0 {
		warn("Override left dns.nameserver empty: keeping the subscription list")

		cfg.DNS.NameServer = nameServers
	}
}

func portOccupied(cfg *config.RawConfig, port int) bool {
	return cfg.Port == port ||
		cfg.SocksPort == port ||
		cfg.RedirPort == port ||
		cfg.TProxyPort == port
}

func mixedPortOverridden(slot string) bool {
	var keys map[string]json.RawMessage

	if err := json.Unmarshal([]byte(slot), &keys); err != nil {
		return false
	}

	_, ok := keys[mixedPortKey]

	return ok
}

func DefaultMixedPort(cfg *config.RawConfig, slots ...string) {
	if cfg.MixedPort != 0 || cfg.Port != 0 || cfg.AllowLan || portOccupied(cfg, defaultMixedPort) {
		return
	}

	if slices.ContainsFunc(slots, mixedPortOverridden) {
		return
	}

	cfg.MixedPort = defaultMixedPort
}

func isSystemNameServer(server string) bool {
	return server == systemNameServer || server == "system"
}

func applyOwnDns(cfg *config.RawConfig, override string, warn Warn) {
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
			warn("Apply own app override: %s", err.Error())
		}
	}

	if len(keys.DNS) > 0 {
		if err := json.Unmarshal(keys.DNS, &cfg.DNS); err != nil {
			warn("Apply own dns override: %s", err.Error())
		}
	}
}

func Dns(cfg *config.RawConfig, warn Warn, slots ...string) {
	if !cfg.DNS.Enable {
		cfg.DNS = config.DefaultRawConfig().DNS
		cfg.DNS.NameServer = defaultNameServers
		cfg.DNS.EnhancedMode = C.DNSFakeIP
		cfg.DNS.FakeIPRange = defaultFakeIPRange
		cfg.DNS.FakeIPFilter = defaultFakeIPFilter

		cfg.ClashForAndroid.AppendSystemDNS = true

		for _, slot := range slots {
			applyOwnDns(cfg, slot, warn)
		}

		if len(cfg.DNS.NameServer) == 0 {
			warn("Override left dns.nameserver empty: using the built-in list")

			cfg.DNS.NameServer = defaultNameServers
		}

		cfg.DNS.Enable = true
	}

	if cfg.ClashForAndroid.AppendSystemDNS && !slices.ContainsFunc(cfg.DNS.NameServer, isSystemNameServer) {
		cfg.DNS.NameServer = append(cfg.DNS.NameServer, systemNameServer)
	}
}
