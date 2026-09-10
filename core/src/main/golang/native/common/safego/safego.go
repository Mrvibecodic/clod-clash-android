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
			defer reportPanic(name, r, capturedStack())

			defer func() {
				if rr := recover(); rr != nil {
					reportPanic(name+" recovery", rr, nil)
				}
			}()

			onPanic(r)
		}
	}
}

func capturedStack() (stack []byte) {
	defer func() {
		if recover() != nil {
			stack = nil
		}
	}()

	return debug.Stack()
}

func reportPanic(name string, r any, stack []byte) {
	go func() {
		defer func() {
			_ = recover()
		}()

		if len(stack) == 0 {
			log.Errorln("[APP] %s panicked: %v", name, r)

			return
		}

		log.Errorln("[APP] %s panicked: %v\n%s", name, r, string(stack))
	}()
}

func Go(name string, body func()) {
	go func() {
		defer Guard(name, func() {})()

		body()
	}()
}
