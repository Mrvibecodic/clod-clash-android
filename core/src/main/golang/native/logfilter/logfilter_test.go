package logfilter

import (
	"testing"

	"github.com/metacubex/mihomo/log"
)

func TestAppPrefixAlwaysPasses(t *testing.T) {
	levels := []log.LogLevel{log.DEBUG, log.INFO, log.WARNING, log.ERROR, log.SILENT}

	for _, subscribed := range levels {
		for _, level := range levels {
			if !Passes("[APP] something", level, subscribed) {
				t.Fatalf("[APP] dropped at level %v, subscribed %v", level, subscribed)
			}
		}
	}
}

func TestWarningAndErrorAlwaysPass(t *testing.T) {
	levels := []log.LogLevel{log.DEBUG, log.INFO, log.WARNING, log.ERROR, log.SILENT}

	for _, subscribed := range levels {
		for _, level := range []log.LogLevel{log.WARNING, log.ERROR} {
			if !Passes("core message", level, subscribed) {
				t.Fatalf("level %v dropped, subscribed %v", level, subscribed)
			}
		}
	}
}

func TestVerbosityStillObeysSubscription(t *testing.T) {
	cases := []struct {
		level      log.LogLevel
		subscribed log.LogLevel
		want       bool
	}{
		{log.DEBUG, log.DEBUG, true},
		{log.DEBUG, log.INFO, false},
		{log.DEBUG, log.WARNING, false},
		{log.DEBUG, log.ERROR, false},
		{log.DEBUG, log.SILENT, false},
		{log.INFO, log.DEBUG, true},
		{log.INFO, log.INFO, true},
		{log.INFO, log.WARNING, false},
		{log.INFO, log.ERROR, false},
		{log.INFO, log.SILENT, false},
	}

	for _, c := range cases {
		if got := Passes("core message", c.level, c.subscribed); got != c.want {
			t.Fatalf("level %v, subscribed %v: got %v, want %v", c.level, c.subscribed, got, c.want)
		}
	}
}

func TestSilentMessageKeepsFollowingSubscription(t *testing.T) {
	if !Passes("core message", log.SILENT, log.SILENT) {
		t.Fatal("SILENT message dropped at SILENT subscription")
	}

	if Passes("core message", log.SILENT, log.DEBUG) == false {
		t.Fatal("SILENT message dropped at DEBUG subscription")
	}
}
