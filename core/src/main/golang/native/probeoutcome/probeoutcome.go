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
