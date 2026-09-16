package main

//#include "bridge.h"
import "C"

import (
	"runtime"
	"unsafe"

	"cfa/native/common/safego"
	"cfa/native/config"
)

type remoteValidCallback struct {
	callback unsafe.Pointer
}

func (r *remoteValidCallback) reportStatus(json string) {
	C.fetch_report(r.callback, marshalString(json))
}

//export fetchAndValid
func fetchAndValid(callback unsafe.Pointer, path, url C.c_string, force, probe C.int) {
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
	config.SetSecureChannel(enabled != 0)
}

//export load
func load(completable unsafe.Pointer, path C.c_string) {
	p := C.GoString(path)

	safego.Go("load", func() {
		err := func() (err error) {
			defer safego.GuardWith("load", func(r any) {
				err = panicError("load", r)
			})()

			return config.Load(p)
		}()

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
	c := C.GoString(content)

	config.WriteOverride(config.OverrideSlot(slot), c)
}

//export clearOverride
func clearOverride(slot C.int) {
	config.ClearOverride(config.OverrideSlot(slot))
}
