//go:build debug
// +build debug

package main

import (
	"net/http"
	_ "net/http/pprof"

	"github.com/metacubex/mihomo/log"
)

func init() {
	go func() {
		// Только петля: тег debug плагин сборки добавляет сам для любого
		// отладочного варианта, а профилировщик на всех интерфейсах отдаёт
		// дампы всей локальной сети, а при поднятом туннеле и дальше.
		log.Debugln("pprof service listen at: 127.0.0.1:8888")

		_ = http.ListenAndServe("127.0.0.1:8888", nil)
	}()
}
