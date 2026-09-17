package main

/*
#cgo LDFLAGS: -llog

#include "bridge.h"
*/
import "C"

import (
	"runtime"
	"runtime/debug"

	"cfa/native/common/safego"
	"cfa/native/config"
	"cfa/native/delegate"
	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/log"
)

func main() {
	panic("Stub!")
}

//export coreInit
func coreInit(home, versionName, gitVersion C.c_string, sdkVersion C.int) {
	h := C.GoString(home)
	v := C.GoString(versionName)
	g := C.GoString(gitVersion)
	s := int(sdkVersion)

	delegate.Init(h, v, g, s)

	tunnel.StartHeartbeat()

	reset()
}

//export reset
func reset() {
	defer guard("reset", func() {})()

	tunnel.CancelHealthChecks()
	tunnel.CloseProviders()
	config.LoadDefault()
	tunnel.ResetStatistic()
	tunnel.CloseAllConnections()

	safego.Go("resetGc", func() {
		runtime.GC()
		debug.FreeOSMemory()
	})
}

//export forceGc
func forceGc() {
	defer guard("forceGc", func() {})()

	safego.Go("forceGc", func() {
		log.Infoln("[APP] request force GC")

		runtime.GC()
		debug.FreeOSMemory()
	})
}
