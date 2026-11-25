package probeoutcome

import (
	"context"
	"errors"
	"fmt"
	"testing"
)

func TestClassify(t *testing.T) {
	nodeTimeout := errors.New("i/o timeout")

	cases := []struct {
		name     string
		probeErr error
		ctxErr   error
		want     Outcome
	}{
		{"успех без отмены", nil, nil, Alive},
		{"успех после отмены раунда", nil, context.Canceled, Alive},
		{"успех после исчерпания бюджета", nil, context.DeadlineExceeded, Alive},
		{"узел не ответил, раунд жив", nodeTimeout, nil, Failed},
		{"узел не ответил, раунд отменён", nodeTimeout, context.Canceled, Superseded},
		{"узел не ответил, бюджет исчерпан", nodeTimeout, context.DeadlineExceeded, Expired},
		{"таймаут своей пробы при живом раунде", context.DeadlineExceeded, nil, Failed},
		{"обёрнутая отмена раунда", nodeTimeout, fmt.Errorf("round: %w", context.Canceled), Superseded},
		{"обёрнутое исчерпание бюджета", nodeTimeout, fmt.Errorf("round: %w", context.DeadlineExceeded), Expired},
	}

	for _, c := range cases {
		if got := Classify(c.probeErr, c.ctxErr); got != c.want {
			t.Errorf("%s: Classify(%v, %v) = %s, ожидалось %s", c.name, c.probeErr, c.ctxErr, got, c.want)
		}
	}
}

func TestStringKnowsEveryOutcome(t *testing.T) {
	for _, o := range []Outcome{Alive, Failed, Expired, Superseded} {
		if o.String() == "unknown" {
			t.Errorf("исход %d не имеет имени", int(o))
		}
	}
}

func TestZeroOutcomeIsSuperseded(t *testing.T) {
	var zero Outcome

	if zero != Superseded {
		t.Errorf("нулевой исход = %s, ожидался %s", zero, Superseded)
	}
}

func TestInterrupted(t *testing.T) {
	reset := errors.New("core reset")
	superseded := errors.New("superseded")

	cases := []struct {
		name                 string
		candidates, measured int
		cause                error
		want                 bool
	}{
		{"мерить нечего", 0, 0, nil, false},
		{"измерено хоть что-то", 5, 1, nil, false},
		{"не измерено ничего, бюджет вышел", 5, 0, nil, true},
		{"не измерено ничего, смена сети", 5, 0, context.Canceled, true},
		{"не измерено ничего, сброс ядра", 5, 0, reset, false},
		{"не измерено ничего, обёрнутый сброс ядра", 5, 0, fmt.Errorf("stop: %w", reset), false},
		{"не измерено ничего, погашен новым", 5, 0, superseded, false},
	}

	for _, c := range cases {
		if got := Interrupted(c.candidates, c.measured, c.cause, reset, superseded); got != c.want {
			t.Errorf("%s: Interrupted = %v, ожидалось %v", c.name, got, c.want)
		}
	}
}
