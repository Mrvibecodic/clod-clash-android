package main

//#include "bridge.h"
import "C"

import (
	"encoding/json"
	"unsafe"

	"cfa/native/app"
	"cfa/native/common/safego"
	"cfa/native/tunnel"
)

//export queryTunnelState
func queryTunnelState() *C.char {
	mode := tunnel.QueryMode()

	response := &struct {
		Mode string `json:"mode"`
	}{mode}

	return marshalJson(response)
}

//export queryNow
func queryNow(upload, download *C.uint64_t) {
	defer guard("queryNow", func() {})()

	up, down := tunnel.Now()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryTotal
func queryTotal(upload, download *C.uint64_t) {
	defer guard("queryTotal", func() {})()

	up, down := tunnel.Total()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryGroupNames
func queryGroupNames(excludeNotSelectable C.int) (result *C.char) {
	defer guard("queryGroupNames", func() {})()

	return marshalJson(tunnel.QueryProxyGroupNames(excludeNotSelectable != 0))
}

//export queryGroup
func queryGroup(name C.c_string, sortMode C.c_string) (result *C.char) {
	defer guard("queryGroup", func() {})()

	n := C.GoString(name)
	s := C.GoString(sortMode)

	mode := tunnel.Default

	switch s {
	case "Title":
		mode = tunnel.Title
	case "Delay":
		mode = tunnel.Delay
	}

	response := tunnel.QueryProxyGroup(n, mode, app.SubtitlePattern())

	if response == nil {
		return nil
	}

	return marshalJson(response)
}

//export healthCheck
func healthCheck(completable unsafe.Pointer, name C.c_string) {
	defer guard("healthCheck", func() {})()

	n := C.GoString(name)

	safego.Go("healthCheck", func() {
		err := func() (err error) {
			defer safego.GuardWith("healthCheck", func(r any) {
				err = panicError("healthCheck", r)
			})()

			return tunnel.HealthCheck(n)
		}()

		C.complete(completable, marshalError(err))

		C.release_object(completable)
	})
}

//export healthCheckGroups
func healthCheckGroups(completable unsafe.Pointer, request C.c_string) {
	defer guard("healthCheckGroups", func() {})()

	r := C.GoString(request)

	safego.Go("healthCheckGroups", func() {
		err := func() (err error) {
			defer safego.GuardWith("healthCheckGroups", func(r any) {
				err = panicError("healthCheckGroups", r)
			})()

			var req struct {
				Groups  []string `json:"groups"`
				Exclude []string `json:"exclude"`
				Force   bool     `json:"force"`
			}

			if err := json.Unmarshal([]byte(r), &req); err != nil {
				return err
			}

			return tunnel.HealthCheckGroups(req.Groups, req.Exclude, req.Force)
		}()

		C.complete(completable, marshalError(err))

		C.release_object(completable)
	})
}

//export testProfileDelays
func testProfileDelays(path C.c_string) (result *C.char) {
	defer guard("testProfileDelays", func() {})()

	return marshalJson(tunnel.TestProfileDelays(C.GoString(path)))
}

//export notifyNetworkChanged
func notifyNetworkChanged(closeConnections C.int, holdProbes C.int) {
	defer guard("notifyNetworkChanged", func() {})()

	tunnel.OnNetworkChanged(closeConnections != 0, holdProbes != 0)
}

//export probeCurrentNodes
func probeCurrentNodes() {
	defer guard("probeCurrentNodes", func() {})()

	tunnel.ProbeCurrentNodes()
}

//export recoverDeadNodes
func recoverDeadNodes(force C.int) {
	defer guard("recoverDeadNodes", func() {})()

	f := force != 0

	safego.Go("recoverDeadNodes", func() {
		tunnel.RecoverDeadNodes(f)
	})
}

//export notifyNetworkReady
func notifyNetworkReady() {
	defer guard("notifyNetworkReady", func() {})()

	tunnel.NoteNetworkReady()
}

//export patchSelector
func patchSelector(selector, name C.c_string) (result C.int) {
	defer guard("patchSelector", func() {})()

	s := C.GoString(selector)
	n := C.GoString(name)

	return C.int(tunnel.PatchSelector(s, n))
}

//export queryProviders
func queryProviders() (result *C.char) {
	defer guard("queryProviders", func() {})()

	return marshalJson(tunnel.QueryProviders())
}

//export updateProvider
func updateProvider(completable unsafe.Pointer, pType C.c_string, name C.c_string) {
	defer guard("updateProvider", func() {})()

	t := C.GoString(pType)
	n := C.GoString(name)

	safego.Go("updateProvider", func() {
		err := func() (err error) {
			defer safego.GuardWith("updateProvider", func(r any) {
				err = panicError("updateProvider", r)
			})()

			return tunnel.UpdateProvider(t, n)
		}()

		C.complete(completable, marshalError(err))

		C.release_object(completable)
	})
}

//export suspend
func suspend(suspended C.int) {
	defer guard("suspend", func() {})()

	tunnel.Suspend(suspended != 0)
}
