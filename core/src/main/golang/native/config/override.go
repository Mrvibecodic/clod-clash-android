package config

import (
	"sync"

	"cfa/native/config/overlay"
	"cfa/native/config/overridefile"
	"cfa/native/config/panel"

	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

type OverrideSlot int

const (
	OverrideSlotPersist OverrideSlot = iota
	OverrideSlotSession
)

const defaultPersistOverride = `{}`
const defaultSessionOverride = `{}`

var (
	sessionLock     sync.RWMutex
	sessionOverride = defaultSessionOverride
)

// Файл читается один раз за жизнь процесса и держится в памяти, как и сессионный
// слот: одна загрузка конфига спрашивает его несколько раз, а пишет его только ядро.
var persistOverride = sync.OnceValue(func() *overridefile.Store {
	store := overridefile.NewStore(constant.Path.Resolve("override.json"), defaultPersistOverride)

	if _, err := store.Read(); err != nil {
		log.Warnln("Read override: %s", err.Error())
	}

	return store
})

// ReadOverride — для сборки конфига: нечитаемые настройки не должны мешать
// подключению, поэтому здесь они заводские; причина один раз уходит в журнал
// при чтении с диска.
func ReadOverride(slot OverrideSlot) string {
	content, err := QueryOverride(slot)
	if err != nil {
		return defaultPersistOverride
	}

	return content
}

// QueryOverride — для экрана настроек: ошибку чтения отдаёт наружу.
func QueryOverride(slot OverrideSlot) (string, error) {
	switch slot {
	case OverrideSlotPersist:
		return persistOverride().Read()
	case OverrideSlotSession:
		sessionLock.RLock()
		defer sessionLock.RUnlock()

		return sessionOverride, nil
	}

	return "", nil
}

func WriteOverride(slot OverrideSlot, content string) error {
	switch slot {
	case OverrideSlotPersist:
		return persistOverride().Write(content)
	case OverrideSlotSession:
		sessionLock.Lock()
		sessionOverride = content
		sessionLock.Unlock()
	}

	return nil
}

func ClearOverride(slot OverrideSlot) error {
	switch slot {
	case OverrideSlotPersist:
		return persistOverride().Remove()
	case OverrideSlotSession:
		sessionLock.Lock()
		sessionOverride = defaultSessionOverride
		sessionLock.Unlock()
	}

	return nil
}

func ProviderValues(profileDir, session string) (overlay.Values, error) {
	template, err := panel.ReadProfileFile(profileDir, panel.ProfileConfigFile)
	if err != nil {
		return overlay.Values{}, err
	}

	persist, err := QueryOverride(OverrideSlotPersist)
	if err != nil {
		return overlay.Values{}, err
	}

	return overlay.Differences(template, persist, overlay.Mode{
		Persist: modeName(overrideMode(persist)),
		Choice:  modeName(overrideMode(session)),
		Locked:  modeLocked(profileDir, panel.Read(profileDir)),
	})
}

func ResetProviderValues(keys []string) error {
	return persistOverride().Update(func(content string) (string, error) {
		return overlay.Clear(content, overlay.Resettable(keys))
	})
}
