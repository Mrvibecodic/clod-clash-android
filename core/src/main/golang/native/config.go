package main

//#include "bridge.h"
import "C"

import (
	"runtime"
	"unsafe"

	"cfa/native/common/safego"
	"cfa/native/config"
	"cfa/native/tunnel"
)

type remoteValidCallback struct {
	callback unsafe.Pointer
}

func (r *remoteValidCallback) reportStatus(json string) {
	C.fetch_report(r.callback, marshalString(json))
}

//export fetchAndValid
func fetchAndValid(callback unsafe.Pointer, path, url C.c_string, force, probe C.int) {
	defer guard("fetchAndValid", func() {})()

	p, u := C.GoString(path), C.GoString(url)

	safego.Go("fetchAndValid", func() {
		cb := &remoteValidCallback{callback: callback}

		err := func() (err error) {
			defer safego.GuardWith("fetchAndValid", func(r any) {
				err = panicError("fetch", r)
			})()

			return config.FetchAndValid(p, u, force != 0, probe != 0, cb.reportStatus)
		}()

		C.fetch_complete(callback, marshalError(err))

		C.release_object(callback)

		runtime.GC()
	})
}

//export setSecureChannel
func setSecureChannel(enabled C.int) {
	defer guard("setSecureChannel", func() {})()

	config.SetSecureChannel(enabled != 0)
}

//export load
func load(completable unsafe.Pointer, path C.c_string) {
	defer guard("load", func() {})()

	p := C.GoString(path)

	safego.Go("load", func() {
		err := func() (err error) {
			defer safego.GuardWith("load", func(r any) {
				err = panicError("load", r)
			})()

			return config.Load(p)
		}()

		if err == nil {
			tunnel.CancelHealthChecksOfOldConfig()
		}

		C.complete(completable, marshalError(err))

		C.release_object(completable)

		runtime.GC()
	})
}

//export readOverride
func readOverride(slot C.int) *C.char {
	return C.CString(config.ReadOverride(config.OverrideSlot(slot)))
}

//export writeOverride
func writeOverride(slot C.int, content C.c_string) {
	defer guard("writeOverride", func() {})()

	c := C.GoString(content)

	config.WriteOverride(config.OverrideSlot(slot), c)
}

//export queryModeOf
func queryModeOf(path, session C.c_string) *C.char {
	defer guard("queryModeOf", func() {})()

	return marshalJson(config.QueryMode(C.GoString(path), C.GoString(session)))
}

//export switchMode
func switchMode(path, session C.c_string) (result C.int) {
	defer guard("switchMode", func() {})()

	if config.SwitchMode(C.GoString(path), C.GoString(session)) {
		return 1
	}

	return 0
}

//export markProfileUpdated
func markProfileUpdated(path C.c_string, interval C.int64_t) {
	defer guard("markProfileUpdated", func() {})()

	config.MarkUpdated(C.GoString(path), int64(interval))
}

//export clearOverride
func clearOverride(slot C.int) {
	defer guard("clearOverride", func() {})()

	config.ClearOverride(config.OverrideSlot(slot))
}

//export reloadGeoData
func reloadGeoData() {
	defer guard("reloadGeoData", func() {})()

	config.ReloadGeoData()
}
