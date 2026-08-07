package config

import (
	"os"
	P "path"
	"runtime"
	"strings"
	"sync"

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

// parseMutex сериализует разбор конфигурации.
//
// `config.ParseRawConfig` в mihomo на время разбора подменяет ГЛОБАЛЬНЫЕ
// настройки ядра (`temporaryUpdateGeneral` в `hub/executor`): режим, DisableIPv6,
// интерфейс, routing-mark, User-Agent, адреса geodata — и в конце возвращает то,
// что было на входе. Пока разбор один, это незаметно. Но с появлением проверки
// задержек до подключения разборов стало два, и они могут наложиться: проверка
// захватывает настройки простоя, следом загрузка профиля применяет боевые,
// а откат проверки затирает их обратно — туннель остаётся в чужом режиме.
//
// Мьютекс накрывает разбор ВМЕСТЕ с применением: сериализовать одни разборы
// мало, потому что откат может лечь и на `hub.ApplyConfig`, случившийся между
// захватом и откатом соседнего разбора. Поэтому у `Load`/`LoadDefault` лок
// держится до конца применения.
//
// `sync.Mutex` не рекурсивный, отсюда пара `Parse` (берёт лок) и `parseLocked`
// (уже под локом): вложенный вызов был бы мгновенным самодедлоком.
var parseMutex sync.Mutex

func Parse(rawConfig *config.RawConfig) (*config.Config, error) {
	parseMutex.Lock()
	defer parseMutex.Unlock()

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
	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	logDns(rawCfg)

	// Разбор и применение — под одним локом: между ними нельзя пускать
	// чужой разбор, иначе его откат затрёт только что применённое.
	parseMutex.Lock()

	cfg, err := parseLocked(rawCfg)
	if err == nil {
		// like hub.Parse()
		hub.ApplyConfig(cfg)
	}

	parseMutex.Unlock()

	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	app.ApplySubtitlePattern(rawCfg.ClashForAndroid.UiSubtitlePattern)

	runtime.GC()

	return nil
}

func LoadDefault() {
	// Тоже под локом: это `ParseRawConfig` из mihomo, и он подменяет
	// глобальные настройки ядра ровно так же. Зовётся при каждом `reset()`,
	// то есть на каждом подключении и отключении.
	parseMutex.Lock()
	defer parseMutex.Unlock()

	cfg, err := config.Parse([]byte{})
	if err != nil {
		panic(err.Error())
	}

	hub.ApplyConfig(cfg)
}
