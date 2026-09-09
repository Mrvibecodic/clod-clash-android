package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/proxy"

	"github.com/metacubex/mihomo/log"
)

//export startHttp
func startHttp(listenAt C.c_string) *C.char {
	l := C.GoString(listenAt)

	listen, err := proxy.Start(l)
	if err != nil {
		log.Warnln("Local http inbound at %s is unavailable: %s", l, err.Error())

		return nil
	}

	return C.CString(listen)
}

//export stopHttp
func stopHttp() {
	proxy.Stop()
}
