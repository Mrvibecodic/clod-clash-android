package probeoutcome

import (
	"context"
	"errors"
)

type Outcome int

const (
	Superseded Outcome = iota
	Alive
	Failed
	Expired
)

func (o Outcome) String() string {
	switch o {
	case Alive:
		return "alive"
	case Failed:
		return "failed"
	case Expired:
		return "expired"
	case Superseded:
		return "superseded"
	default:
		return "unknown"
	}
}

// Interrupted — замер, которому было что мерить, не измерил ничего: человеку
// это ошибка, а не пустой список. Кроме отмен с причиной из quiet — там итог
// не нужен: ядро сброшено, конфиг заменён, замер погашен новым
func Interrupted(candidates, measured int, cause error, quiet ...error) bool {
	if candidates == 0 || measured > 0 {
		return false
	}

	for _, q := range quiet {
		if errors.Is(cause, q) {
			return false
		}
	}

	return true
}

func Classify(probeErr error, ctxErr error) Outcome {
	switch {
	case probeErr == nil:
		return Alive
	case errors.Is(ctxErr, context.Canceled):
		return Superseded
	case errors.Is(ctxErr, context.DeadlineExceeded):
		return Expired
	default:
		return Failed
	}
}
