package app

import (
	"net"
	"sync/atomic"
	"syscall"

	"cfa/native/platform"
)

// Пара колбэков сессии меняется со стороны JNI, а читается на каждом
// соединении: подменяется целиком, чтобы никто не увидел половину от новой.
type tunContext struct {
	markSocket     func(fd int)
	querySocketUid func(protocol int, source, target string) int
}

var tunCallbacks atomic.Pointer[tunContext]

func MarkSocket(fd int) {
	tunCallbacks.Load().markSocket(fd)
}

func QuerySocketUid(source, target net.Addr) int {
	var protocol int

	switch source.Network() {
	case "udp", "udp4", "udp6":
		protocol = syscall.IPPROTO_UDP
	case "tcp", "tcp4", "tcp6":
		protocol = syscall.IPPROTO_TCP
	default:
		return -1
	}

	if PlatformVersion() < 29 {
		return platform.QuerySocketUidFromProcFs(source, target)
	}

	return tunCallbacks.Load().querySocketUid(protocol, source.String(), target.String())
}

func ApplyTunContext(markSocket func(fd int), querySocketUid func(int, string, string) int) {
	if markSocket == nil {
		markSocket = func(fd int) {}
	}

	if querySocketUid == nil {
		querySocketUid = func(int, string, string) int { return -1 }
	}

	tunCallbacks.Store(&tunContext{markSocket: markSocket, querySocketUid: querySocketUid})
}

func init() {
	ApplyTunContext(nil, nil)
}
