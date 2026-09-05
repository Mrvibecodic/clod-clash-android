package config

import (
	"errors"
	"os"
	P "path"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"

	"cfa/native/app"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/log"
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

func UnmarshalAndPatch(profilePath string) (*config.RawConfig, error) {
	configPath := P.Join(profilePath, "config.yaml")

	configData, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}

	rawConfig, err := config.UnmarshalRawConfig(configData)
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

	loaded.Store(true)

	app.ApplySubtitlePattern(rawCfg.ClashForAndroid.UiSubtitlePattern)

	runtime.GC()

	return nil
}

func LoadDefault() {
	pendingGeneration.Store(loadGeneration.Load() + 1)

	loadGeneration.Add(1)

	applyPendingDefault()
}
