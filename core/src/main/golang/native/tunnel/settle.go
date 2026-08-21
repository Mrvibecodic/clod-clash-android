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

	heartbeatFreezeGap = 45 * time.Second
)

var (
	settleUntil atomic.Int64

	heartbeatOnce sync.Once
)

func NoteNetworkChange() {
	until := time.Now().Add(networkSettleWindow).UnixNano()

	settleUntil.Store(until)

	C.SetProbeHoldUntil(until)
}

func NoteNetworkReady() {
	until := time.Now().Add(networkReadyGrace).UnixNano()

	for {
		cur := settleUntil.Load()
		if cur <= until {
			return
		}

		if settleUntil.CompareAndSwap(cur, until) {
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
	last := time.Now().UnixNano()

	C.ProbeBeat(last)

	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()

	for range ticker.C {
		now := time.Now().UnixNano()

		if gap := time.Duration(now - last); gap > heartbeatFreezeGap {
			NoteNetworkChange()

			log.Infoln("Resumed after %s pause: probes held for %s", gap.Round(time.Second), networkSettleWindow)
		}

		last = now

		C.ProbeBeat(now)
	}
}

func waitNetworkSettled(ctx context.Context) error {
	for {
		wait := time.Until(time.Unix(0, settleUntil.Load()))
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
