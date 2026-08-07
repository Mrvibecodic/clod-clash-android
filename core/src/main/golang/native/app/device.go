package app

import "strings"

// Сведения об устройстве для панели.
//
// Remnawave считает устройства по заголовку `x-hwid` и показывает рядом
// с каждым, что это за устройство. Считает их Kotlin — сырой идентификатор
// живёт в `Settings.Secure.ANDROID_ID`, и добираться до него из Go пришлось бы
// через JNI ради одного значения. Здесь они только хранятся и отдаются тому,
// кто собирает запрос подписки.
//
// Пустой `hwid` означает «опознание выключено»: тогда НИ ОДИН из заголовков
// не отправляется — половина набора хуже, чем ничего, панель по ней устройство
// всё равно не опознает, а данные об устройстве уже уйдут.
var device struct {
	hwid      string
	os        string
	osVersion string
	model     string
}

func ApplyDeviceInfo(hwid, os, osVersion, model string) {
	device.hwid = strings.TrimSpace(hwid)
	device.os = strings.TrimSpace(os)
	device.osVersion = strings.TrimSpace(osVersion)
	device.model = strings.TrimSpace(model)
}

// DeviceHeaders — заголовки опознания устройства, пустая карта при выключенном.
func DeviceHeaders() map[string]string {
	if device.hwid == "" {
		return nil
	}

	headers := map[string]string{"x-hwid": device.hwid}

	if device.os != "" {
		headers["x-device-os"] = device.os
	}

	if device.osVersion != "" {
		headers["x-ver-os"] = device.osVersion
	}

	if device.model != "" {
		headers["x-device-model"] = device.model
	}

	return headers
}
