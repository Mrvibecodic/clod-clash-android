package main

//#include "bridge.h"
import "C"

import (
	"time"
	"unsafe"

	"cfa/native/app"
	"cfa/native/common/safego"
	"cfa/native/logfilter"
	"cfa/native/redact"

	"github.com/metacubex/mihomo/log"
)

type message struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    int64  `json:"time"`
}

func init() {
	safego.Go("logcatBridge", func() {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			if !logfilter.Passes(msg.Payload, msg.LogLevel, log.Level()) {
				continue
			}

			cPayload := C.CString(redact.Text(msg.Payload))

			switch msg.LogLevel {
			case log.INFO:
				C.log_info(cPayload)
			case log.ERROR:
				C.log_error(cPayload)
			case log.WARNING:
				C.log_warn(cPayload)
			case log.DEBUG:
				C.log_debug(cPayload)
			case log.SILENT:
				C.log_verbose(cPayload)
			}
		}
	})
}

//export subscribeLogcat
func subscribeLogcat(remote unsafe.Pointer) {
	safego.Go("subscribeLogcat", func() {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			if !logfilter.Passes(msg.Payload, msg.LogLevel, log.Level()) {
				continue
			}

			rMsg := &message{
				Level:   msg.LogLevel.String(),
				Message: redact.Text(msg.Payload),
				Time:    time.Now().UnixNano() / 1000 / 1000,
			}

			if C.logcat_received(remote, marshalJson(rMsg)) != 0 {
				C.release_object(remote)

				log.Debugln("Logcat subscriber closed")

				break
			}
		}
	})

	log.Infoln("[APP] ClodClash %s, logcat level: %s", app.VersionName(), log.Level().String())
}
