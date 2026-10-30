package chanx

import (
	"errors"
	"fmt"
	"strings"
	"testing"
)

func TestOverLogsWhyTheDirectRetryFailed(t *testing.T) {
	tunnelErr := errors.New("tunnel: EOF")
	directErr := errors.New("direct: connect: network is unreachable")

	for _, row := range []struct {
		name    string
		reached bool
	}{
		{"direct did not reach the server", false},
		{"direct reached the server and failed", true},
	} {
		var warned []string
		warn := func(format string, args ...any) { warned = append(warned, fmt.Sprintf(format, args...)) }

		attempt := func(direct bool) (bool, error) {
			if direct {
				return row.reached, directErr
			}

			return false, tunnelErr
		}

		_, err := Over(attempt, func(err error, reached bool) bool { return Detour(err, reached) }, warn)
		if err == nil {
			t.Fatalf("%s: Over succeeded", row.name)
		}

		if !strings.Contains(strings.Join(warned, "\n"), directErr.Error()) {
			t.Fatalf("%s: the direct error is not in the log: %q", row.name, warned)
		}
	}
}

func TestOverDoesNotLogADirectFailureThatDidNotHappen(t *testing.T) {
	var warned []string
	warn := func(format string, args ...any) { warned = append(warned, fmt.Sprintf(format, args...)) }

	attempt := func(direct bool) (bool, error) {
		if direct {
			return true, nil
		}

		return false, errors.New("tunnel: EOF")
	}

	if _, err := Over(attempt, func(err error, reached bool) bool { return Detour(err, reached) }, warn); err != nil {
		t.Fatalf("Over failed: %v", err)
	}

	if strings.Contains(strings.Join(warned, "\n"), "failed as well") {
		t.Fatalf("a successful direct retry is logged as failed: %q", warned)
	}
}
