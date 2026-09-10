package safego

import (
	"errors"
	"testing"
	"time"

	"github.com/metacubex/mihomo/log"
)

func TestGoRunsBodyOnce(t *testing.T) {
	calls := make(chan struct{}, 4)

	Go("body", func() {
		calls <- struct{}{}
	})

	select {
	case <-calls:
	case <-time.After(5 * time.Second):
		t.Fatal("body was not called")
	}

	select {
	case <-calls:
		t.Fatal("body was called more than once")
	case <-time.After(100 * time.Millisecond):
	}
}

func TestGoSurvivesPanicInBody(t *testing.T) {
	done := make(chan struct{})

	Go("panicking", func() {
		defer close(done)

		panic("boom")
	})

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("body did not run")
	}

	time.Sleep(100 * time.Millisecond)
}

func TestGuardCallsOnPanic(t *testing.T) {
	called := false

	func() {
		defer Guard("guarded", func() { called = true })()

		panic(errors.New("boom"))
	}()

	if !called {
		t.Fatal("onPanic was not called")
	}
}

func TestGuardIsSilentWithoutPanic(t *testing.T) {
	called := false

	func() {
		defer Guard("guarded", func() { called = true })()
	}()

	if called {
		t.Fatal("onPanic was called without a panic")
	}
}

func TestGuardSurvivesPanicInOnPanic(t *testing.T) {
	func() {
		defer Guard("guarded", func() { panic("secondary") })()

		panic("primary")
	}()
}

func TestGuardCallsOnPanicWhileLogIsStuck(t *testing.T) {
	sub := log.Subscribe()
	defer log.UnSubscribe(sub)

	for i := 0; i < 256; i++ {
		func() {
			defer Guard("filler", func() {})()

			panic("filler")
		}()
	}

	called := make(chan struct{})

	go func() {
		defer Guard("guarded", func() { close(called) })()

		panic("boom")
	}()

	select {
	case <-called:
	case <-time.After(5 * time.Second):
		t.Fatal("onPanic was not called while the log channel was stuck")
	}
}
