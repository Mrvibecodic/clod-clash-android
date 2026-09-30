package tunnel

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	"cfa/native/common/safego"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

const (
	networkSettleWindow = 5 * time.Second

	networkReadyGrace = time.Second

	heartbeatInterval = 10 * time.Second

	settleStep = 250 * time.Millisecond
)

var (
	// settleUntil is compared on the monotonic clock: a wall clock jump must
	// neither stretch nor cut the hold window.
	settleUntil atomic.Pointer[time.Time]

	// networkReadyAt is wall clock nanoseconds, the same scale as the heartbeat
	// gap it is compared against.
	networkReadyAt atomic.Int64

	heartbeatOnce sync.Once
)

func NoteNetworkChange() {
	until := time.Now().Add(networkSettleWindow)

	settleUntil.Store(&until)

	C.SetProbeHoldUntil(until)
}

func NoteNetworkReady() {
	now := time.Now().UnixNano()

	networkReadyAt.Store(now)

	C.ProbeBeat(now)

	until := time.Now().Add(networkReadyGrace)

	// Удержание могло начать и само ядро, проснувшись: подтверждённая сеть
	// укорачивает его в любом случае
	C.CapProbeHoldUntil(until)

	for {
		cur := settleUntil.Load()
		if cur == nil || !until.Before(*cur) {
			return
		}

		if settleUntil.CompareAndSwap(cur, &until) {
			return
		}
	}
}

func StartHeartbeat() {
	heartbeatOnce.Do(func() {
		safego.Go("heartbeat", heartbeat)
	})
}

func heartbeat() {
	// Wall clock on purpose: the monotonic clock stops while the device sleeps,
	// and a gap between beats is how sleep is detected. The gap is taken from
	// the core's last beat: a probe that found the sleep first has already
	// started the hold there, and the heartbeat must not start another.
	C.ProbeBeat(time.Now().UnixNano())

	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()

	for range ticker.C {
		now := time.Now().UnixNano()

		if gap := time.Duration(now - C.ProbeLastBeat()); gap > C.ProbeFreezeGap {
			if time.Duration(now-networkReadyAt.Load()) > C.ProbeFreezeGap {
				NoteNetworkChange()

				log.Infoln("Resumed after %s pause: probes held for %s", gap.Round(time.Second), networkSettleWindow)
			} else {
				log.Infoln("Resumed after %s pause: network already confirmed, probes not held", gap.Round(time.Second))
			}
		}

		C.ProbeBeat(now)
	}
}

func waitNetworkSettled(ctx context.Context) error {
	for {
		until := settleUntil.Load()
		if until == nil {
			return nil
		}

		wait := time.Until(*until)
		if wait <= 0 {
			return nil
		}

		// Подтверждённая сеть сокращает окно: ждём шагами, чтобы проба
		// пошла сразу после нового конца, а не после прежнего
		if wait > settleStep {
			wait = settleStep
		}

		timer := time.NewTimer(wait)

		select {
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()

			return ctx.Err()
		}
	}
}
