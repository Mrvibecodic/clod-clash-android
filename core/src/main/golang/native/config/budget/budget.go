package budget

import "time"

const (
	MinAttempt    = 10 * time.Second
	DirectReserve = 10 * time.Second
	Total         = 180 * time.Second
)

type Budget struct {
	deadline time.Time
}

func New(now time.Time) *Budget {
	return Within(now, Total)
}

func Within(now time.Time, total time.Duration) *Budget {
	return &Budget{deadline: now.Add(total)}
}

func (b *Budget) Deadline() time.Time {
	return b.deadline
}

func (b *Budget) Remaining(now time.Time) time.Duration {
	return b.deadline.Sub(now)
}

func (b *Budget) Ensure(now time.Time, minimum time.Duration) {
	if floor := now.Add(minimum); b.deadline.Before(floor) {
		b.deadline = floor
	}
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
