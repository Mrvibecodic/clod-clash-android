package budget

import "time"

const (
	MinAttempt    = 10 * time.Second
	DirectReserve = 10 * time.Second
	ConfigShare   = 120 * time.Second
	ProviderShare = 60 * time.Second
	Total         = ConfigShare + ProviderShare
)

type Budget struct {
	deadline time.Time
	phase    time.Time
}

func New(now time.Time) *Budget {
	return Within(now, Total)
}

func Within(now time.Time, total time.Duration) *Budget {
	return &Budget{deadline: now.Add(total), phase: now.Add(configShare(total))}
}

func configShare(total time.Duration) time.Duration {
	if total >= Total {
		return ConfigShare
	}

	if total <= 0 {
		return total
	}

	return time.Duration(total.Milliseconds()*ConfigShare.Milliseconds()/Total.Milliseconds()) * time.Millisecond
}

func (b *Budget) Deadline() time.Time {
	return b.deadline
}

func (b *Budget) Remaining(now time.Time) time.Duration {
	remaining := b.deadline.Sub(now)

	if phase := b.phase.Sub(now); phase < remaining {
		remaining = phase
	}

	return remaining
}

func (b *Budget) EnterProviderPhase() {
	b.phase = b.deadline
}

func (b *Budget) Window(now time.Time, limit time.Duration) (time.Duration, bool) {
	remaining := b.Remaining(now)
	if remaining < MinAttempt {
		return 0, false
	}

	if remaining < limit {
		limit = remaining
	}

	return limit, true
}

func TunnelShare(window time.Duration) time.Duration {
	if window-DirectReserve < MinAttempt {
		return window
	}

	return window - DirectReserve
}

func (b *Budget) DirectWindow(now time.Time, deadline time.Time, minimum time.Duration) (time.Duration, bool) {
	limit := deadline.Sub(now)
	if limit < minimum {
		limit = minimum
	}

	if limit <= 0 {
		return 0, false
	}

	return b.Window(now, limit)
}
