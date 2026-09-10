package budget

import (
	"testing"
	"time"
)

var start = time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)

func TestTunnelShareKeepsAttemptMeaningful(t *testing.T) {
	for _, row := range []struct {
		window time.Duration
		want   time.Duration
	}{
		{30 * time.Second, 20 * time.Second},
		{60 * time.Second, 50 * time.Second},
		{20 * time.Second, 10 * time.Second},
		{19 * time.Second, 19 * time.Second},
		{12 * time.Second, 12 * time.Second},
	} {
		if got := TunnelShare(row.window); got != row.want {
			t.Fatalf("TunnelShare(%s) = %s, want %s", row.window, got, row.want)
		}
	}
}

func TestDirectRetryStaysInsideAddressWindow(t *testing.T) {
	for _, row := range []struct {
		name    string
		total   time.Duration
		window  time.Duration
		spent   time.Duration
		minimum time.Duration
		want    time.Duration
		ok      bool
	}{
		{"nothing spent", Total, 30 * time.Second, 0, DirectReserve, 30 * time.Second, true},
		{"tunnel used its share", Total, 30 * time.Second, 20 * time.Second, DirectReserve, 10 * time.Second, true},
		{"tunnel overran its share", Total, 30 * time.Second, 29 * time.Second, DirectReserve, 10 * time.Second, true},
		{"window already over", Total, 30 * time.Second, 30 * time.Second, DirectReserve, 10 * time.Second, true},
		{"total budget is the tighter cap", 12 * time.Second, 30 * time.Second, 0, DirectReserve, 12 * time.Second, true},
		{"total budget exhausted", 5 * time.Second, 30 * time.Second, 0, DirectReserve, 0, false},
		{"second retry without the reserve", Total, 30 * time.Second, 30 * time.Second, 0, 0, false},
	} {
		b := Within(start, row.total)
		deadline := start.Add(row.window)

		got, ok := b.DirectWindow(start.Add(row.spent), deadline, row.minimum)

		if ok != row.ok {
			t.Fatalf("%s: ok = %v, want %v", row.name, ok, row.ok)
		}

		if ok && got != row.want {
			t.Fatalf("%s: window = %s, want %s", row.name, got, row.want)
		}
	}
}

func TestWindowRefusesBelowTheFloor(t *testing.T) {
	b := New(start)

	if _, ok := b.Window(start.Add(Total-MinAttempt+time.Second), 30*time.Second); ok {
		t.Fatal("a window shorter than the floor was granted")
	}

	if got, ok := b.Window(start.Add(Total-MinAttempt), 30*time.Second); !ok || got != MinAttempt {
		t.Fatalf("window at the floor = %s, %v", got, ok)
	}
}

func TestWindowSurvivesAClockJump(t *testing.T) {
	b := New(start)

	if _, ok := b.Window(start.Add(time.Hour), 30*time.Second); ok {
		t.Fatal("the clock jumped forward past the deadline and a window was still granted")
	}

	if got, ok := b.Window(start.Add(-time.Hour), 30*time.Second); !ok || got != 30*time.Second {
		t.Fatalf("the clock jumped backwards: window = %s, %v", got, ok)
	}
}
