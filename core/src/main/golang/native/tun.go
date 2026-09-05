package main

//#include "bridge.h"
import "C"

import (
	"context"
	"io"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"golang.org/x/sync/semaphore"

	"cfa/native/app"
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

func (t *remoteTun) markSocket(fd int) {
	_ = t.limit.Acquire(context.Background(), 1)
	defer t.limit.Release(1)

	if t.closed.Load() {
		return
	}

	C.mark_socket(t.callback, C.int(fd))
}

func (t *remoteTun) querySocketUid(protocol int, source, target string) int {
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

	if !acquired {
		log.Warnln("Stop tun: socket callbacks still busy after %s, leaking callback", closeTimeout)

		return
	}

	t.limit.Release(4)

	C.release_object(t.callback)
}

//export startTun
func startTun(fd C.int, stack, gateway, portal, dns C.c_string, callback unsafe.Pointer) (result C.int) {
	started := false

	// Паника внутри tun.Start оставила бы применённый контекст и глобальную
	// ссылку на колбэки мёртвой сессии: снимаем их на любом выходе без успеха.
	remote := &remoteTun{callback: callback, limit: semaphore.NewWeighted(4)}

	defer guard("startTun", func() { result = 1 })()

	rTunLock.Lock()
	defer rTunLock.Unlock()

	defer func() {
		if !started {
			app.ApplyTunContext(nil, nil)

			remote.close()
		}
	}()

	if old := rTun; old != nil {
		rTun = nil
		old.close()
	}

	f := int(fd)
	s := C.GoString(stack)
	g := C.GoString(gateway)
	p := C.GoString(portal)
	d := C.GoString(dns)

	app.ApplyTunContext(remote.markSocket, remote.querySocketUid)

	closer, err := tun.Start(f, s, g, p, d)
	if err != nil {
		log.Errorln("Start tun: %s", err.Error())

		return 1
	}

	remote.closer = closer

	rTun = remote

	started = true

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
