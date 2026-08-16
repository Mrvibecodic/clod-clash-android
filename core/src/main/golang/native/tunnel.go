package main

import "C"

import (
	"unsafe"

	"cfa/native/app"
	"cfa/native/tunnel"
)

func queryTunnelState() *C.char {
	mode := tunnel.QueryMode()

	response := &struct {
		Mode string `json:"mode"`
	}{mode}

	return marshalJson(response)
}

func queryNow(upload, download *C.uint64_t) {
	up, down := tunnel.Now()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

func queryTotal(upload, download *C.uint64_t) {
	up, down := tunnel.Total()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

func queryGroupNames(excludeNotSelectable C.int) *C.char {
	return marshalJson(tunnel.QueryProxyGroupNames(excludeNotSelectable != 0))
}

func queryGroup(name C.c_string, sortMode C.c_string) *C.char {
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

func healthCheck(completable unsafe.Pointer, name C.c_string) {
	go func(name string) {
		tunnel.HealthCheck(name)

		C.complete(completable, nil)
	}(C.GoString(name))
}

func testProfileDelays(path C.c_string) *C.char {
	return marshalJson(tunnel.TestProfileDelays(C.GoString(path)))
}

func healthCheckAll() {
	tunnel.HealthCheckAll()
}

func notifyNetworkChanged(closeConnections C.int) {
	tunnel.OnNetworkChanged(closeConnections != 0)
}

func probeCurrentNodes() {
	tunnel.ProbeCurrentNodes()
}

func patchSelector(selector, name C.c_string) C.int {
	s := C.GoString(selector)
	n := C.GoString(name)

	if tunnel.PatchSelector(s, n) {
		return 1
	}

	return 0
}

func queryProviders() *C.char {
	return marshalJson(tunnel.QueryProviders())
}

func updateProvider(completable unsafe.Pointer, pType C.c_string, name C.c_string) {
	go func(pType, name string) {
		C.complete(completable, marshalString(tunnel.UpdateProvider(pType, name)))

		C.release_object(completable)
	}(C.GoString(pType), C.GoString(name))
}

func suspend(suspended C.int) {
	tunnel.Suspend(suspended != 0)
}
