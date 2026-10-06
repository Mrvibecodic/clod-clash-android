package report

import (
	"os"
	"sync"
	"time"

	"github.com/metacubex/mihomo/log"
)

// Накопленное одной подписки живёт в памяти: файл читается один раз, пишется
// не чаще saveEvery, при закрытии часа, после отправки, при смене или
// остановке сбора. При аварийном завершении теряется не больше saveEvery.

const saveEvery = 5 * 60

var (
	cacheMu      sync.Mutex
	cachePath    string
	cache        *Store
	cacheDirty   bool
	cacheSavedAt int64
	// Файл был на диске, когда читали: исчез — его удалила служба вместе с
	// подпиской, возвращать нечего.
	cacheHad bool
)

// withStore — накопленное подписки в работу; save — писать в файл сразу.
func withStore(path string, now int64, save bool, work func(*Store)) {
	cacheMu.Lock()
	defer cacheMu.Unlock()

	if cache == nil || cachePath != path {
		flushLocked(now)

		cachePath = path
		cache = Load(path)
		cacheDirty = false
		cacheSavedAt = now
		_, err := os.Stat(path)
		cacheHad = err == nil
	}

	work(cache)
	cacheDirty = true

	if save || now-cacheSavedAt >= saveEvery || hourOf(now) != hourOf(cacheSavedAt) {
		flushLocked(now)
	}
}

// flushLocked — в файл, если есть что. Файл, который был и исчез, удалила
// служба вместе с подпиской — не возвращается.
func flushLocked(now int64) {
	if cache == nil || !cacheDirty {
		return
	}

	if _, err := os.Stat(cachePath); cacheHad && err != nil {
		cacheDirty = false

		return
	}

	if err := Save(cachePath, cache); err != nil {
		log.Warnln("[Report] the measurements were not saved: %s", err.Error())

		return
	}

	cacheDirty = false
	cacheSavedAt = now
	cacheHad = true
}

// Flush — накопленное в файл: сбор сменил подписку или остановлен.
func Flush() {
	cacheMu.Lock()
	defer cacheMu.Unlock()

	flushLocked(time.Now().Unix())
}

// forget — в файл и из памяти вон.
func forget() {
	cacheMu.Lock()
	defer cacheMu.Unlock()

	flushLocked(time.Now().Unix())

	cache = nil
	cachePath = ""
}
