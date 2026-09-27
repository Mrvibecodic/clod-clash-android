package main

//#include "bridge.h"
import "C"

import (
	"context"
	"io"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sync/semaphore"

	"cfa/native/app"
	"cfa/native/common/safego"
	"cfa/native/tun"

	"github.com/metacubex/mihomo/log"
)

const closeTimeout = 3 * time.Second

var rTunLock sync.Mutex
var rTun *remoteTun

type remoteTun struct {
	closer   io.Closer
	callback unsafe.Pointer

	closed atomic.Bool
	limit  *semaphore.Weighted
}

// Закрытый туннель не встаёт в очередь семафора: там может ждать фоновое
// освобождение колбэка из close().
func (t *remoteTun) markSocket(fd int) {
	if t.closed.Load() {
		return
	}

	_ = t.limit.Acquire(context.Background(), 1)
	defer t.limit.Release(1)

	if t.closed.Load() {
		return
	}

	C.mark_socket(t.callback, C.int(fd))
}

func (t *remoteTun) querySocketUid(protocol int, source, target string) int {
	if t.closed.Load() {
		return -1
	}

	_ = t.limit.Acquire(context.Background(), 1)
	defer t.limit.Release(1)

	if t.closed.Load() {
		return -1
	}

	return int(C.query_socket_uid(t.callback, C.int(protocol), C.CString(source), C.CString(target)))
}

func (t *remoteTun) close() {
	ctx, cancel := context.WithTimeout(context.Background(), closeTimeout)
	defer cancel()

	acquired := t.limit.Acquire(ctx, 4) == nil

	t.closed.Store(true)

	if t.closer != nil {
		_ = t.closer.Close()
	}

	app.ApplyTunContext(nil, nil)

	// Колбэк держит всю службу VPN, а освободить его, пока в нём стоит
	// обращение к Android, нельзя: отпускаем, когда вернётся последнее.
	if !acquired {
		log.Warnln("Stop tun: socket callbacks still busy after %s, releasing callback once they return", closeTimeout)

		safego.Go("releaseTunCallback", func() {
			_ = t.limit.Acquire(context.Background(), 4)

			t.limit.Release(4)

			C.release_object(t.callback)
		})

		return
	}

	t.limit.Release(4)

	C.release_object(t.callback)
}

// Незащищённые сокеты ядра, открытые до установки VPN, после неё система
// заворачивает в наш же туннель, и они виснут до тайм-аута: так умирают пробы
// узлов, которые ядро само запускает при применении конфига. Поэтому колбэки
// держит сессия службы с самого её начала, а не поднятый туннель.
//
//export attachSocketCallbacks
func attachSocketCallbacks(callback unsafe.Pointer) {
	remote := &remoteTun{callback: callback, limit: semaphore.NewWeighted(4)}

	defer guard("attachSocketCallbacks", func() {})()

	rTunLock.Lock()
	defer rTunLock.Unlock()

	if old := rTun; old != nil {
		rTun = nil
		old.close()
	}

	app.ApplyTunContext(remote.markSocket, remote.querySocketUid)

	rTun = remote
}

//export startTun
func startTun(fd C.int, stack, gateway, portal, dns C.c_string) (result C.int) {
	handedOver := false

	defer guard("startTun", func() { result = 1 })()

	rTunLock.Lock()
	defer rTunLock.Unlock()

	defer func() {
		if !handedOver {
			_ = syscall.Close(int(fd))
		}
	}()

	session := rTun
	if session == nil {
		log.Errorln("Start tun: socket callbacks not attached")

		return 1
	}

	if session.closer != nil {
		_ = session.closer.Close()

		session.closer = nil
	}

	f := int(fd)
	s := C.GoString(stack)
	g := C.GoString(gateway)
	p := C.GoString(portal)
	d := C.GoString(dns)

	handedOver = true

	closer, err := tun.Start(f, s, g, p, d)
	if err != nil {
		log.Errorln("Start tun: %s", err.Error())

		return 1
	}

	session.closer = closer

	return 0
}

//export stopTun
func stopTun() {
	defer guard("stopTun", func() {})()

	rTunLock.Lock()
	defer rTunLock.Unlock()

	if old := rTun; old != nil {
		rTun = nil
		old.close()
	}
}
