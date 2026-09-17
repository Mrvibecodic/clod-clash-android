package safego

import (
	"bytes"
	"runtime/debug"
	"sync"

	"github.com/metacubex/mihomo/log"
)

const reportQueueSize = 64

type panicReport struct {
	name  string
	cause any
	stack []byte
}

var (
	reportQueue  = make(chan panicReport, reportQueueSize)
	reporterOnce sync.Once
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

func reportPanic(name string, cause any, stack []byte) {
	reporterOnce.Do(func() {
		go processReports()
	})

	select {
	case reportQueue <- panicReport{name: name, cause: cause, stack: stack}:
	default:
	}
}

func processReports() {
	for report := range reportQueue {
		writeReport(report)
	}
}

func writeReport(report panicReport) {
	defer func() {
		_ = recover()
	}()

	log.Errorln("[APP] %s panicked: %v", report.name, report.cause)

	for _, part := range stackParts(report.stack, stackPartSize) {
		log.Errorln("[APP] %s stack:\n%s", report.name, part)
	}
}

// Системный журнал Android режет запись примерно на четырёх килобайтах, а стек
// паники длиннее: он уходит частями, разрезанными по границам строк.
const stackPartSize = 3000

func stackParts(stack []byte, size int) []string {
	var parts []string

	for len(stack) > 0 {
		end := len(stack)

		if end > size {
			end = size

			if cut := bytes.LastIndexByte(stack[:end], '\n'); cut > 0 {
				end = cut + 1
			}
		}

		parts = append(parts, string(stack[:end]))

		stack = stack[end:]
	}

	return parts
}

func Go(name string, body func()) {
	go func() {
		defer Guard(name, func() {})()

		body()
	}()
}
