package main

//#include "bridge.h"
import "C"

import (
	"errors"
	"unsafe"

	"cfa/native/app"

	"github.com/metacubex/mihomo/log"
)

func openRemoteContent(url string) (int, error) {
	u := C.CString(url)
	e := (*C.char)(C.malloc(1024))

	log.Debugln("Open remote url: %s", url)

	defer C.free(unsafe.Pointer(e))

	fd := C.open_content(u, e, 1024)

	if fd < 0 {
		return -1, errors.New(C.GoString(e))
	}

	return int(fd), nil
}

//export notifyDnsChanged
func notifyDnsChanged(dnsList C.c_string) {
	defer guard("notifyDnsChanged", func() {})()

	d := C.GoString(dnsList)

	app.NotifyDnsChanged(d)
}

//export notifyInstalledAppsChanged
func notifyInstalledAppsChanged(uids C.c_string) {
	defer guard("notifyInstalledAppsChanged", func() {})()

	u := C.GoString(uids)

	app.NotifyInstallAppsChanged(u)
}

//export notifyTimeZoneChanged
func notifyTimeZoneChanged(name C.c_string, offset C.int) {
	defer guard("notifyTimeZoneChanged", func() {})()

	app.NotifyTimeZoneChanged(C.GoString(name), int(offset))
}

func init() {
	app.ApplyContentContext(openRemoteContent)
}

//export setDeviceInfo
func setDeviceInfo(hwid, os, osVersion, model C.c_string) {
	defer guard("setDeviceInfo", func() {})()

	app.ApplyDeviceInfo(C.GoString(hwid), C.GoString(os), C.GoString(osVersion), C.GoString(model))
}
