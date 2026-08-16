package main

import "C"

import (
	"cfa/native/proxy"
)

func startHttp(listenAt C.c_string) *C.char {
	l := C.GoString(listenAt)

	listen, err := proxy.Start(l)
	if err != nil {
		return nil
	}

	return C.CString(listen)
}

func stopHttp() {
	proxy.Stop()
}
