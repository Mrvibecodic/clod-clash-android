package main

/*
#cgo LDFLAGS: -llog

#include "bridge.h"
*/
import "C"

import (
	"runtime"
	"runtime/debug"

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
	tunnel.CancelHealthChecks()
	diagnosticsStop()
	if err := config.RotateExternalControllerSecret(); err != nil {
		panic(err)
	}
	config.LoadDefault()
	tunnel.ResetStatistic()
	tunnel.CloseAllConnections()

	go func() {
		runtime.GC()
		debug.FreeOSMemory()
	}()
}

//export startDiagnostics
func startDiagnostics(endpoint, tunnelAuth, controllerSecret C.c_string, remotePort C.int) {
	diagnosticsStart(
		C.GoString(endpoint),
		C.GoString(tunnelAuth),
		C.GoString(controllerSecret),
		int(remotePort),
	)
}

//export stopDiagnostics
func stopDiagnostics() {
	diagnosticsStop()
}

//export queryDiagnostics
func queryDiagnostics() *C.char {
	return C.CString(diagnosticsQuery())
}

//export recordDiagnosticsEvent
func recordDiagnosticsEvent(code C.int) {
	diagnosticsRecordEvent(int(code))
}

//export forceGc
func forceGc() {
	go func() {
		log.Infoln("[APP] request force GC")

		runtime.GC()
		debug.FreeOSMemory()
	}()
}
