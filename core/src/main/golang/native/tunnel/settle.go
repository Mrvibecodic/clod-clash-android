package tunnel

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

const (
	networkSettleWindow = 5 * time.Second

	networkReadyGrace = time.Second

	heartbeatInterval = 10 * time.Second
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
	networkReadyAt.Store(time.Now().UnixNano())

	until := time.Now().Add(networkReadyGrace)

	for {
		cur := settleUntil.Load()
		if cur == nil || !until.Before(*cur) {
			return
		}

		if settleUntil.CompareAndSwap(cur, &until) {
			C.SetProbeHoldUntil(until)

			return
		}
	}
}

func StartHeartbeat() {
	heartbeatOnce.Do(func() {
		go heartbeat()
	})
}

func heartbeat() {
	// Wall clock on purpose: the monotonic clock stops while the device sleeps,
	// and a gap between ticks is how sleep is detected.
	last := time.Now().UnixNano()

	C.ProbeBeat(last)

	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()

	for range ticker.C {
		now := time.Now().UnixNano()

		if gap := time.Duration(now - last); gap > C.ProbeFreezeGap {
			if time.Duration(now-networkReadyAt.Load()) > C.ProbeFreezeGap {
				NoteNetworkChange()

				log.Infoln("Resumed after %s pause: probes held for %s", gap.Round(time.Second), networkSettleWindow)
			} else {
				log.Infoln("Resumed after %s pause: network already confirmed, probes not held", gap.Round(time.Second))
			}
		}

		last = now

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

		timer := time.NewTimer(wait)

		select {
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()

			return ctx.Err()
		}
	}
}
