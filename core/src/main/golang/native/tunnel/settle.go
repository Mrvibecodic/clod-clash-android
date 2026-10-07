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
	// networkReadyAt is wall clock nanoseconds, the same scale as the heartbeat
	// gap it is compared against.
	networkReadyAt atomic.Int64

	heartbeatOnce sync.Once

	// beatMu — разрыв пульса проверяют по одному: сон, найденный и пульсом,
	// и пробой, не отмечается дважды
	beatMu sync.Mutex
)

func NoteNetworkChange() {
	C.SetProbeHoldUntil(time.Now().Add(networkSettleWindow))
}

func NoteNetworkReady() {
	now := time.Now().UnixNano()

	networkReadyAt.Store(now)

	C.ProbeBeat(now)

	// Удержание могло начать и само ядро, проснувшись: подтверждённая сеть
	// укорачивает его в любом случае
	C.CapProbeHoldUntil(time.Now().Add(networkReadyGrace))
}

func StartHeartbeat() {
	heartbeatOnce.Do(func() {
		safego.Go("heartbeat", heartbeat)
	})
}

func heartbeat() {
	C.ProbeBeat(time.Now().UnixNano())

	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()

	for range ticker.C {
		beat()
	}
}

// beat — отметка «процесс не спит». Часы стенные: монотонные во сне стоят, а
// разрыв между отметками и есть признак сна. Разрыв берётся от последней
// отметки ядра: проба ядра, нашедшая сон первой, уже начала там удержание, и
// второе начинать не нужно
func beat() {
	beatMu.Lock()
	defer beatMu.Unlock()

	now := time.Now().UnixNano()

	if last := C.ProbeLastBeat(); last != 0 {
		if gap := time.Duration(now - last); gap > C.ProbeFreezeGap {
			if time.Duration(now-networkReadyAt.Load()) > C.ProbeFreezeGap {
				NoteNetworkChange()

				log.Infoln("Resumed after %s pause: probes held for %s", gap.Round(time.Second), networkSettleWindow)
			} else {
				log.Infoln("Resumed after %s pause: network already confirmed, probes not held", gap.Round(time.Second))
			}
		}
	}

	C.ProbeBeat(now)
}

// waitNetworkSettled ждёт конца удержания проб. Удержание одно, у ядра: его
// ставят смена сети и пробуждение, кто бы его ни заметил первым, — проба
// клиента иначе шла бы на пробуждении, найденном только ядром, и ядро
// выбрасывало бы её провал
func waitNetworkSettled(ctx context.Context) error {
	// Сон, замеченный пробой раньше пульса, — та же смена сети: отметка и
	// запись в журнал, как у пульса
	beat()

	// Подтверждённая сеть сокращает окно: ждём шагами, чтобы проба
	// пошла вскоре после нового конца, а не после прежнего
	for C.ProbeHolding(time.Now()) {
		timer := time.NewTimer(settleStep)

		select {
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()

			return ctx.Err()
		}
	}

	return nil
}
