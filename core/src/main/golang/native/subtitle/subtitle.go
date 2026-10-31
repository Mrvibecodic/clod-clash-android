package subtitle

import (
	"sync/atomic"
	"time"

	"github.com/dlclark/regexp2"

	"github.com/metacubex/mihomo/log"
)

// Шаблон приходит из подписки: без предела перебор на одном имени занял бы
// поток запроса навсегда. Предел — на одно имя сервера, обычный шаблон
// укладывается в микросекунды.
const matchTimeout = time.Second

var pattern atomic.Pointer[regexp2.Regexp]

func Apply(source string) {
	if source == "" {
		pattern.Store(nil)

		return
	}

	if o := pattern.Load(); o != nil && o.String() == source {
		return
	}

	reg, err := regexp2.Compile(source, regexp2.IgnoreCase|regexp2.Compiled)
	if err != nil {
		pattern.Store(nil)

		log.Warnln("Ignore unsupported ui-subtitle-pattern: %s", err.Error())

		return
	}

	reg.MatchTimeout = matchTimeout

	pattern.Store(reg)
}

// Split — имя сервера по ui-subtitle-pattern: совпадение уходит из заголовка
// в подпись. ok=false — шаблона нет или он не совпал. Шаблон, не уложившийся
// в предел, снимается до следующей загрузки конфига.
func Split(name string) (title, subtitle string, ok bool) {
	reg := pattern.Load()
	if reg == nil {
		return "", "", false
	}

	runes := []rune(name)

	match, err := reg.FindRunesMatch(runes)
	if err != nil {
		// Часы предела в regexp2 после простоя дают ложный тайм-аут вызову,
		// начатому одновременно с другим: снимается шаблон, не уложившийся дважды.
		match, err = reg.FindRunesMatch(runes)
	}

	if err != nil {
		if pattern.CompareAndSwap(reg, nil) {
			log.Warnln("Ignore ui-subtitle-pattern: matching a server name took longer than %s", matchTimeout)
		}

		return "", "", false
	}

	if match == nil {
		return "", "", false
	}

	return string(runes[:match.Index]) + string(runes[match.Index+match.Length:]),
		string(runes[match.Index : match.Index+match.Length]), true
}
