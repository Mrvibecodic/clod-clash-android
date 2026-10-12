package app

import (
	"strings"
	"sync"
)

// Карточка устройства для загрузки подписки: её задаёт служба перед загрузкой,
// а читают горутины загрузки — поэтому под замком
var device struct {
	sync.RWMutex
	hwid      string
	os        string
	osVersion string
	model     string
}

func ApplyDeviceInfo(hwid, os, osVersion, model string) {
	device.Lock()
	defer device.Unlock()

	device.hwid = hwid
	device.os = os
	device.osVersion = osVersion
	device.model = model
}

func DeviceHeaders() map[string]string {
	device.RLock()
	defer device.RUnlock()

	return CardHeaders(device.hwid, device.os, device.osVersion, device.model)
}

// CardHeaders — заголовки опознания по карточке устройства; без HWID карточки
// нет вовсе (опознание выключено)
func CardHeaders(hwid, os, osVersion, model string) map[string]string {
	hwid = strings.TrimSpace(hwid)
	if hwid == "" {
		return nil
	}

	headers := map[string]string{"x-hwid": hwid}

	if os = strings.TrimSpace(os); os != "" {
		headers["x-device-os"] = os
	}

	if osVersion = strings.TrimSpace(osVersion); osVersion != "" {
		headers["x-ver-os"] = osVersion
	}

	if model = strings.TrimSpace(model); model != "" {
		headers["x-device-model"] = model
	}

	return headers
}
