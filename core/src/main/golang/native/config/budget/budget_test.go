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
		name     string
		spent    time.Duration
		window   time.Duration
		provider bool
		minimum  time.Duration
		want     time.Duration
		ok       bool
	}{
		{"nothing spent", 0, 30 * time.Second, false, DirectReserve, 30 * time.Second, true},
		{"tunnel used its share", 20 * time.Second, 30 * time.Second, false, DirectReserve, 10 * time.Second, true},
		{"tunnel overran its share", 29 * time.Second, 30 * time.Second, false, DirectReserve, 10 * time.Second, true},
		{"window already over", 30 * time.Second, 30 * time.Second, false, DirectReserve, 10 * time.Second, true},
		{"overall budget is the tighter cap", 168 * time.Second, 190 * time.Second, true, DirectReserve, 12 * time.Second, true},
		{"overall budget exhausted", 175 * time.Second, 190 * time.Second, true, DirectReserve, 0, false},
		{"configuration share exhausted", 115 * time.Second, 190 * time.Second, false, DirectReserve, 0, false},
		{"second retry without the reserve", 30 * time.Second, 30 * time.Second, false, 0, 0, false},
	} {
		b := New(start)
		if row.provider {
			b.EnterProviderPhase()
		}

		got, ok := b.DirectWindow(start.Add(row.spent), start.Add(row.window), row.minimum)

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
	b.EnterProviderPhase()

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

func TestConfigurationPhaseCannotEatTheProviderShare(t *testing.T) {
	for _, spent := range []time.Duration{
		0,
		30 * time.Second,
		60 * time.Second,
		110 * time.Second,
		ConfigShare,
	} {
		b := New(start)
		now := start.Add(spent)

		if got := b.Remaining(now); got > ConfigShare-spent {
			t.Fatalf("spent %s: the configuration phase sees %s, more than its share", spent, got)
		}

		b.EnterProviderPhase()

		if got := b.Remaining(now); got < ProviderShare {
			t.Fatalf("spent %s: providers got %s, less than %s", spent, got, ProviderShare)
		}

		if b.Deadline() != start.Add(Total) {
			t.Fatalf("spent %s: the overall deadline moved to %s", spent, b.Deadline())
		}
	}
}

func TestConfigurationPhaseIsCapped(t *testing.T) {
	b := New(start)

	if got, ok := b.Window(start, fetchTimeoutForTest); !ok || got != fetchTimeoutForTest {
		t.Fatalf("first address window = %s, %v", got, ok)
	}

	if got, ok := b.Window(start.Add(100*time.Second), fetchTimeoutForTest); !ok || got != 20*time.Second {
		t.Fatalf("late address window = %s, %v", got, ok)
	}

	if _, ok := b.Window(start.Add(ConfigShare-MinAttempt+time.Second), fetchTimeoutForTest); ok {
		t.Fatal("an address window was granted past the configuration share")
	}
}

const fetchTimeoutForTest = 60 * time.Second

func TestProviderShareScalesWithASmallerBudget(t *testing.T) {
	b := Within(start, 60*time.Second)

	if got := b.Remaining(start); got != 40*time.Second {
		t.Fatalf("configuration share of a 60 s budget = %s, want 40s", got)
	}

	b.EnterProviderPhase()

	if got := b.Remaining(start.Add(40 * time.Second)); got != 20*time.Second {
		t.Fatalf("provider share of a 60 s budget = %s, want 20s", got)
	}
}

func TestSecureChannelRoundsShareTheAddressWindow(t *testing.T) {
	b := New(start)
	deadline := start.Add(30 * time.Second)

	spent := time.Duration(0)
	granted := []time.Duration{}

	for round := 0; round < 3; round++ {
		limit, ok := b.DirectWindow(start.Add(spent), deadline, 0)
		if !ok {
			break
		}

		window := TunnelShare(limit)

		granted = append(granted, window)

		spent += window
	}

	want := []time.Duration{20 * time.Second, 10 * time.Second}

	if len(granted) != len(want) {
		t.Fatalf("rounds granted = %v, want %v", granted, want)
	}

	for index, window := range granted {
		if window != want[index] {
			t.Fatalf("round %d window = %s, want %s", index+1, window, want[index])
		}
	}

	if spent != 30*time.Second {
		t.Fatalf("the address window was exceeded: %s", spent)
	}
}

func TestLastConfigurationStepCannotOverrunTheShare(t *testing.T) {
	const step = 10 * time.Second

	b := New(start)

	if got, ok := b.Window(start.Add(ConfigShare-step), step); !ok || got != step {
		t.Fatalf("the last step of the configuration phase got %s, %v", got, ok)
	}

	if _, ok := b.Window(start.Add(ConfigShare-step+time.Second), step); ok {
		t.Fatal("a step was granted past the configuration share")
	}

	b.EnterProviderPhase()

	if got := b.Remaining(start.Add(ConfigShare)); got != ProviderShare {
		t.Fatalf("providers got %s instead of %s", got, ProviderShare)
	}
}
