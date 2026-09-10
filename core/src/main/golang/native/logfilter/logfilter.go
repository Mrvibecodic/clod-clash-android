package logfilter

import (
	"strings"

	"github.com/metacubex/mihomo/log"
)

const AppPrefix = "[APP]"

const minAlwaysLevel = log.WARNING

func Passes(payload string, level log.LogLevel, subscribed log.LogLevel) bool {
	if strings.HasPrefix(payload, AppPrefix) {
		return true
	}

	if level >= minAlwaysLevel && level != log.SILENT {
		return true
	}

	return level >= subscribed
}
