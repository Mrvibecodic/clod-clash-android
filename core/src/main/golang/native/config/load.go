package config

import (
	"errors"
	"strings"
	"sync"
	"sync/atomic"

	"cfa/native/app"
	"cfa/native/config/panel"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

func logDns(cfg *config.RawConfig) {
	bytes, err := yaml.Marshal(&cfg.DNS)
	if err != nil {
		log.Warnln("Marshal dns: %s", err.Error())

		return
	}

	log.Infoln("dns:")

	for _, line := range strings.Split(string(bytes), "\n") {
		log.Infoln("  %s", line)
	}
}

func unmarshalProfile(profilePath string) (*config.RawConfig, error) {
	configData, err := panel.ReadProfileFile(profilePath, panel.ProfileConfigFile)
	if err != nil {
		return nil, err
	}

	return config.UnmarshalRawConfig(configData)
}

func UnmarshalAndPatch(profilePath string) (*config.RawConfig, error) {
	rawConfig, err := unmarshalProfile(profilePath)
	if err != nil {
		return nil, err
	}

	if err := process(rawConfig, profilePath); err != nil {
		return nil, err
	}

	return rawConfig, nil
}

var (
	parseMutex        sync.Mutex
	loadGeneration    atomic.Uint64
	pendingGeneration atomic.Uint64
	loaded            atomic.Bool
	globalDeclared    atomic.Bool
)

var ErrLoadCancelled = errors.New("load cancelled by reset")

func IsLoaded() bool {
	return loaded.Load()
}

func applyDefaultLocked() {
	cfg, err := config.Parse([]byte{})
	if err != nil {
		panic(err.Error())
	}

	loaded.Store(false)

	hub.ApplyConfig(cfg)
}

func applyPendingDefault() {
	for pendingGeneration.Load() != 0 {
		if !parseMutex.TryLock() {
			return
		}

		func() {
			defer parseMutex.Unlock()

			if pendingGeneration.Swap(0) != 0 {
				applyDefaultLocked()
			}
		}()
	}
}

func unlockParse() {
	parseMutex.Unlock()

	applyPendingDefault()
}

func Parse(rawConfig *config.RawConfig) (*config.Config, error) {
	parseMutex.Lock()
	defer unlockParse()

	return parseLocked(rawConfig)
}

func parseLocked(rawConfig *config.RawConfig) (*config.Config, error) {
	cfg, err := config.ParseRawConfig(rawConfig)
	if err != nil {
		return nil, err
	}

	return cfg, nil
}

func Load(path string) error {
	generation := loadGeneration.Load()

	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	logDns(rawCfg)

	prefetchProviders(rawCfg)

	parseMutex.Lock()
	defer unlockParse()

	if loadGeneration.Load() != generation {
		return ErrLoadCancelled
	}

	cfg, err := parseLocked(rawCfg)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	if loadGeneration.Load() != generation {
		for _, p := range cfg.Proxies {
			_ = p.Close()
		}

		DestroyProviders(cfg)

		return ErrLoadCancelled
	}

	pendingGeneration.CompareAndSwap(generation, 0)

	hub.ApplyConfig(cfg)

	declared := globalGroupDeclared(rawCfg)

	globalDeclared.Store(declared)

	if !declared {
		pinGlobalDefault()
	}

	loaded.Store(true)

	app.ApplySubtitlePattern(rawCfg.ClashForAndroid.UiSubtitlePattern)

	return nil
}

func SwitchMode(profileDir, session string) bool {
	if !loaded.Load() {
		return false
	}

	mode, ok := tunnel.ModeMapping[QueryMode(profileDir, session).Mode]
	if !ok {
		return false
	}

	tunnel.SetMode(mode)

	if !globalDeclared.Load() {
		pinGlobalDefault()
	}

	return true
}

func LoadDefault() {
	pendingGeneration.Store(loadGeneration.Load() + 1)

	loadGeneration.Add(1)

	applyPendingDefault()
}
