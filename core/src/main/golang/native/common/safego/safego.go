package safego

import (
	"runtime/debug"

	"github.com/metacubex/mihomo/log"
)

func Guard(name string, onPanic func()) func() {
	return GuardWith(name, func(any) {
		onPanic()
	})
}

func GuardWith(name string, onPanic func(r any)) func() {
	return func() {
		if r := recover(); r != nil {
			log.Errorln("[APP] %s panicked: %v\n%s", name, r, string(debug.Stack()))

			defer func() {
				if rr := recover(); rr != nil {
					log.Errorln("[APP] %s recovery panicked: %v", name, rr)
				}
			}()

			onPanic(r)
		}
	}
}

func Go(name string, body func()) {
	go func() {
		defer Guard(name, func() {})()

		body()
	}()
}
