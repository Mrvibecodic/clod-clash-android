package main

//#include "bridge.h"
import "C"

import (
	"runtime"
	"runtime/debug"
	"unsafe"

	"cfa/native/config"

	"github.com/metacubex/mihomo/log"
)

type remoteValidCallback struct {
	callback unsafe.Pointer
}

func (r *remoteValidCallback) reportStatus(json string) {
	C.fetch_report(r.callback, marshalString(json))
}

//export fetchAndValid
func fetchAndValid(callback unsafe.Pointer, path, url C.c_string, force C.int) {
	go func(path, url string, callback unsafe.Pointer) {
		cb := &remoteValidCallback{callback: callback}

		err := func() (err error) {
			defer func() {
				if r := recover(); r != nil {
					log.Errorln("[APP] fetchAndValid panicked: %v\n%s", r, string(debug.Stack()))

					err = panicError("fetch", r)
				}
			}()

			return config.FetchAndValid(path, url, force != 0, cb.reportStatus)
		}()

		C.fetch_complete(callback, marshalError(err))

		C.release_object(callback)

		runtime.GC()
	}(C.GoString(path), C.GoString(url), callback)
}

//export setSecureChannel
func setSecureChannel(enabled C.int) {
	config.SetSecureChannel(enabled != 0)
}

//export load
func load(completable unsafe.Pointer, path C.c_string) {
	go func(path string) {
		err := func() (err error) {
			defer func() {
				if r := recover(); r != nil {
					log.Errorln("[APP] load panicked: %v\n%s", r, string(debug.Stack()))

					err = panicError("load", r)
				}
			}()

			return config.Load(path)
		}()

		C.complete(completable, marshalError(err))

		C.release_object(completable)

		runtime.GC()
	}(C.GoString(path))
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

//export setAgeSecretKey
func setAgeSecretKey(key C.c_string) {
	if key == nil {
		config.SetGlobalSecretKeys()
		return
	}

	k := C.GoString(key)
	config.SetGlobalSecretKeys(k)
}
