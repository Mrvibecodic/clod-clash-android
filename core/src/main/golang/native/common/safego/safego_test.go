package safego

import (
	"errors"
	"testing"
	"time"
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
