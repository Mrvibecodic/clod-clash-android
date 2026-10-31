package subtitle

import (
	"strings"
	"testing"
	"time"
)

func TestOrdinaryPatternSplitsEveryNameOfLargeSubscription(t *testing.T) {
	Apply(`\[[^\]]*\]`)
	t.Cleanup(func() { Apply("") })

	long := strings.Repeat("x", 4096)

	for i := 0; i < 20000; i++ {
		title, sub, ok := Split("Node " + long + " [vless · reality]")
		if !ok || sub != "[vless · reality]" || title != "Node "+long+" " {
			t.Fatalf("name %d: got %q / %q / %v", i, title, sub, ok)
		}
	}

	if pattern.Load() == nil {
		t.Fatal("an ordinary pattern must stay applied")
	}
}

func TestRunawayPatternIsDroppedInsteadOfHanging(t *testing.T) {
	Apply(`^(a+)+$`)
	t.Cleanup(func() { Apply("") })

	name := strings.Repeat("a", 64) + "!"

	done := make(chan bool, 1)

	go func() {
		_, _, ok := Split(name)
		done <- ok
	}()

	select {
	case ok := <-done:
		if ok {
			t.Fatal("a runaway match must not split the name")
		}
	case <-time.After(matchTimeout + 5*time.Second):
		t.Fatal("matching hangs: no limit on the pattern")
	}

	if pattern.Load() != nil {
		t.Fatal("the runaway pattern must be dropped")
	}

	started := time.Now()

	if _, _, ok := Split(name); ok || time.Since(started) > 100*time.Millisecond {
		t.Fatal("after the drop names are shown whole, without waiting")
	}
}

func TestSamePatternIsAppliedAgainOnNextLoad(t *testing.T) {
	Apply(`^(a+)+$`)
	t.Cleanup(func() { Apply("") })

	pattern.Store(nil)

	Apply(`^(a+)+$`)

	if pattern.Load() == nil {
		t.Fatal("the next config load applies the pattern again")
	}
}

func TestEmptyOrBrokenPatternLeavesNamesWhole(t *testing.T) {
	for _, source := range []string{"", "(unclosed"} {
		Apply(source)

		if _, _, ok := Split("Node [x]"); ok {
			t.Fatalf("pattern %q: names must stay whole", source)
		}
	}
}
