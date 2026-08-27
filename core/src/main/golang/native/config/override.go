package config

import (
	"os"

	"github.com/metacubex/mihomo/constant"
)

type OverrideSlot int

const (
	OverrideSlotPersist OverrideSlot = iota
	OverrideSlotSession
)

const defaultPersistOverride = `{}`
const defaultSessionOverride = `{}`

var sessionOverride = defaultSessionOverride

func overridePersistPath() string {
	return constant.Path.Resolve("override.json")
}

func ReadOverride(slot OverrideSlot) string {
	switch slot {
	case OverrideSlotPersist:
		buf, err := os.ReadFile(overridePersistPath())
		if err != nil {
			return defaultPersistOverride
		}

		return string(buf)
	case OverrideSlotSession:
		return sessionOverride
	}

	return ""
}

func WriteOverride(slot OverrideSlot, content string) {
	switch slot {
	case OverrideSlotPersist:
		tmp := overridePersistPath() + ".tmp"

		file, err := os.OpenFile(tmp, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
		if err != nil {
			return
		}

		if _, err := file.Write([]byte(content)); err != nil {
			_ = file.Close()
			_ = os.Remove(tmp)

			return
		}

		if err := file.Sync(); err != nil {
			_ = file.Close()
			_ = os.Remove(tmp)

			return
		}

		if err := file.Close(); err != nil {
			_ = os.Remove(tmp)

			return
		}

		if err := os.Rename(tmp, overridePersistPath()); err != nil {
			_ = os.Remove(tmp)
		}
	case OverrideSlotSession:
		sessionOverride = content
	}
}

func ClearOverride(slot OverrideSlot) {
	switch slot {
	case OverrideSlotPersist:
		_ = os.Remove(overridePersistPath())
	case OverrideSlotSession:
		sessionOverride = defaultSessionOverride
	}
}
