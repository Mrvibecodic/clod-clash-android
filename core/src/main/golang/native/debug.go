//go:build debug && pprof
// +build debug,pprof

package main

import (
	"net/http"
	_ "net/http/pprof"

	"github.com/metacubex/mihomo/log"
)

func init() {
	go func() {
		// Только в отладочной сборке с -Pclod.pprof=true: 127.0.0.1 на Android
		// общий для всех приложений устройства, и дамп памяти отсюда снимет любое.
		log.Debugln("pprof service listen at: 127.0.0.1:8888")

		_ = http.ListenAndServe("127.0.0.1:8888", nil)
	}()
}
