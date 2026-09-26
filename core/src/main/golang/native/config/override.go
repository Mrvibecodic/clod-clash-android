package config

import (
	"sync"

	"cfa/native/config/overridefile"

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

func overridePersistPath() string {
	return constant.Path.Resolve("override.json")
}

// ReadOverride — для сборки конфига: нечитаемые настройки не должны мешать
// подключению, поэтому здесь они заводские, а причина уходит в журнал.
func ReadOverride(slot OverrideSlot) string {
	content, err := QueryOverride(slot)
	if err != nil {
		log.Warnln("Read override: %s", err.Error())

		return defaultPersistOverride
	}

	return content
}

// QueryOverride — для экрана настроек: ошибку чтения отдаёт наружу.
func QueryOverride(slot OverrideSlot) (string, error) {
	switch slot {
	case OverrideSlotPersist:
		return overridefile.Read(overridePersistPath(), defaultPersistOverride)
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
		return overridefile.Write(overridePersistPath(), content)
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
		return overridefile.Remove(overridePersistPath())
	case OverrideSlotSession:
		sessionLock.Lock()
		sessionOverride = defaultSessionOverride
		sessionLock.Unlock()
	}

	return nil
}
