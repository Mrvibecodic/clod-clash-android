package config

import (
	"errors"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"

	"cfa/native/app"
	"cfa/native/config/panel"
	"cfa/native/report"

	"github.com/metacubex/mihomo/adapter"
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

	if err := process(rawConfig, profilePath, panel.Read(profilePath)); err != nil {
		return nil, err
	}

	return rawConfig, nil
}

var (
	parseMutex        sync.Mutex
	loadGeneration    atomic.Uint64
	pendingGeneration atomic.Uint64
	// loaded — папка подписки, чей конфиг держит ядро; nil — свой конфиг не
	// загружен. Имя папки — UUID подписки: список групп отдаётся с ним
	loaded         atomic.Pointer[string]
	applySeq       atomic.Uint64
	globalDeclared atomic.Bool
)

var ErrLoadCancelled = errors.New("load cancelled by reset")

func IsLoaded() bool {
	return loaded.Load() != nil
}

// ApplySeq — номер смены конфига; нечётный, пока конфиг применяется
func ApplySeq() uint64 {
	return applySeq.Load()
}

// WithProfile выполняет fn, только если ядро держит конфиг подписки profile, —
// под замком смены конфига: на время проверки и fn конфиг не сменится. Чужую
// подписку отсекает до замка: загрузка другой (с закачкой провайдеров) не
// задерживает ответ; загрузку той же дожидается и решает по её итогу
func WithProfile(profile string, fn func()) bool {
	if LoadedProfile() != profile {
		return false
	}

	parseMutex.Lock()
	defer unlockParse()

	if LoadedProfile() != profile {
		return false
	}

	fn()

	return true
}

// LoadedProfile — UUID подписки, чей конфиг держит ядро; пусто — ничей
func LoadedProfile() string {
	if dir := loaded.Load(); dir != nil {
		return filepath.Base(*dir)
	}

	return ""
}

// UseProfileDelayMode — замер без туннеля считает задержку так же, как туннель
// на этом профиле: unified-delay берётся из профиля. Флаг в ядре общий, поэтому
// при загруженном конфиге его не трогаем; следующие загрузка или сброс ставят свой.
func UseProfileDelayMode(rawCfg *config.RawConfig) {
	parseMutex.Lock()
	defer unlockParse()

	if !IsLoaded() {
		adapter.UnifiedDelay.Store(rawCfg.UnifiedDelay)
	}
}

func applyDefaultLocked() {
	cfg, err := config.Parse([]byte{})
	if err != nil {
		panic(err.Error())
	}

	loaded.Store(nil)

	applyLocked(cfg, func() {})
}

// Паника посреди hub.ApplyConfig оставляет туннель на паузе, а прежний конфиг —
// наполовину разобранным новым; паника в доводке после него — ядро на новом
// конфиге, которого служба не признала. В обоих случаях прежнего конфига больше
// нет: признак загрузки снимается, чтобы ни служба, ни switchMode не считали его
// живым.
func applyLocked(cfg *config.Config, finish func()) {
	// Нечётный номер — конфиг меняется: группы и подписка в ядре в этот момент
	// могут быть от разных загрузок
	applySeq.Add(1)
	defer applySeq.Add(1)

	applied := false

	defer func() {
		if !applied {
			loaded.Store(nil)
		}
	}()

	hub.ApplyConfig(cfg)

	finish()

	applied = true
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

	// Докачка до паузы бережёт живой трафик; пока ядро ничего не держит,
	// она лишь отодвигает подъём туннеля — провайдеры тогда берёт сам hub.
	if IsLoaded() {
		prefetchProviders(rawCfg)
	}

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

	forgetLegacyFakeIPs(cfg.DNS)

	pendingGeneration.CompareAndSwap(generation, 0)

	applyLocked(cfg, func() {
		declared := globalGroupDeclared(rawCfg)

		globalDeclared.Store(declared)

		if !declared {
			pinGlobalDefault()
		}

		loaded.Store(&path)

		report.SetNodes(report.NodesOf(rawCfg.Proxy), report.ProvidersOf(rawCfg.ProxyProvider))

		app.ApplySubtitlePattern(rawCfg.ClashForAndroid.UiSubtitlePattern)
	})

	return nil
}

func SwitchMode(profileDir, session string) bool {
	if !IsLoaded() {
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
