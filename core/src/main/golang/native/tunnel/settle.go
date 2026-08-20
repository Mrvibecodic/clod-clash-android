package tunnel

import (
	"context"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

const (
	networkSettleWindow = 5 * time.Second

	networkReadyGrace = time.Second
)

var settleUntil atomic.Int64

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
