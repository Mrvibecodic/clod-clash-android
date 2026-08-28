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

	config.DropProviderParts()

	tunnel.StartHeartbeat()

	reset()
}

//export reset
func reset() {
	defer guard("reset", func() {})()

	tunnel.CancelHealthChecks()
	diagnosticsStop()
	tunnel.CloseProviders()
	if err := config.RotateExternalControllerSecret(); err != nil {
		panic(err)
	}
	config.LoadDefault()
	tunnel.ResetStatistic()
	tunnel.CloseAllConnections()

	safego.Go("resetGc", func() {
		runtime.GC()
		debug.FreeOSMemory()
	})
}

//export startDiagnostics
func startDiagnostics(endpoint, tunnelAuth, controllerSecret C.c_string, remotePort C.int) {
	defer guard("startDiagnostics", func() {})()

	diagnosticsStart(
		C.GoString(endpoint),
		C.GoString(tunnelAuth),
		C.GoString(controllerSecret),
		int(remotePort),
	)
}

//export bootstrapDiagnostics
func bootstrapDiagnostics(endpoint, tunnelAuth C.c_string) (result *C.char) {
	defer guard("bootstrapDiagnostics", func() {})()

	return C.CString(diagnosticsBootstrap(C.GoString(endpoint), C.GoString(tunnelAuth)))
}

//export stopDiagnostics
func stopDiagnostics() {
	defer guard("stopDiagnostics", func() {})()

	diagnosticsStop()
}

//export queryDiagnostics
func queryDiagnostics() (result *C.char) {
	defer guard("queryDiagnostics", func() {})()

	return C.CString(diagnosticsQuery())
}

//export recordDiagnosticsEvent
func recordDiagnosticsEvent(code C.int) {
	defer guard("recordDiagnosticsEvent", func() {})()

	diagnosticsRecordEvent(int(code))
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
