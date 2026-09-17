package safego

import (
	"errors"
	"runtime"
	"strings"
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

func stickLog(t *testing.T) {
	sub := log.Subscribe()

	release := make(chan struct{})
	drained := make(chan struct{})

	go func() {
		defer close(drained)

		<-release

		for range sub {
		}
	}()

	t.Cleanup(func() {
		close(release)

		log.UnSubscribe(sub)

		<-drained
	})
}

func panicUnderGuard(t *testing.T, name string, times int) {
	t.Helper()

	calls := 0

	for i := 0; i < times; i++ {
		func() {
			defer Guard(name, func() { calls++ })()

			panic("boom")
		}()
	}

	if calls != times {
		t.Fatalf("onPanic was called %d times, want %d", calls, times)
	}
}

func TestGuardDoesNotBlockOrLeakWhileLogIsStuck(t *testing.T) {
	stickLog(t)

	panicUnderGuard(t, "saturate", 256)

	time.Sleep(100 * time.Millisecond)

	before := runtime.NumGoroutine()

	startedAt := time.Now()

	panicUnderGuard(t, "stuck", 256)

	if elapsed := time.Since(startedAt); elapsed > 5*time.Second {
		t.Fatalf("guard took %s to return while the log channel was stuck", elapsed)
	}

	time.Sleep(100 * time.Millisecond)

	if after := runtime.NumGoroutine(); after > before+1 {
		t.Fatalf("goroutines grew from %d to %d while the log channel was stuck", before, after)
	}
}

func TestStackPartsKeepEveryByteWithinTheSizeAndCutAtLineEnds(t *testing.T) {
	lines := strings.Repeat("goroutine frame line\n", 500)
	stack := lines + strings.Repeat("x", 250)

	parts := stackParts([]byte(stack), 100)

	if joined := strings.Join(parts, ""); joined != stack {
		t.Fatalf("parts lost or reordered bytes: got %d bytes, want %d", len(joined), len(stack))
	}

	offset := 0

	for i, part := range parts {
		if len(part) == 0 || len(part) > 100 {
			t.Fatalf("part %d has %d bytes, want 1..100", i, len(part))
		}

		offset += len(part)

		if offset <= len(lines) && !strings.HasSuffix(part, "\n") {
			t.Fatalf("part %d is cut in the middle of a line", i)
		}
	}

	if got := stackParts([]byte("\n"+strings.Repeat("x", 250)), 100); strings.Join(got, "") != "\n"+strings.Repeat("x", 250) {
		t.Fatalf("leading newline before a long line lost bytes: %q", got)
	}

	if got := stackParts(nil, 100); len(got) != 0 {
		t.Fatalf("empty stack gave %d parts", len(got))
	}
}
