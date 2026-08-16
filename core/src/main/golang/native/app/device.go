package app

import "strings"

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
