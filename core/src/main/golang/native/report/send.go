package report

import (
	"bytes"
	"compress/gzip"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"

	"github.com/metacubex/mihomo/log"
)

// Отчёт уходит только по защищённому каналу, только после удачного планового
// обновления подписки, а при редких обновлениях — и между ними, и
// не чаще раза в 6 часов, закрытыми часами — от самых
// старых, не больше двух суток за раз и не больше, чем примет прослойка.
// Закрытый час — тот, в который уже ничего не ляжет: перед отправкой сборщик
// дочитывает окно, и граница — час прочитанного. Велик (413) — окно тут же
// уполовинивается; час, который не влез и один, отбрасывается.
// Принят (204) — отправленное удаляется, остальное ждёт следующего раза. Приём выключен в прослойке (403), рано (429)
// или прослойка старая и ответила подпиской — накопленное остаётся, следующая
// попытка через 6 часов. Каналом не ответил никто — попытка при следующей
// отправке.

const (
	sendEvery = 6 * 60 * 60
	// За один отчёт уходит не больше этого от самого старого закрытого часа.
	sendWindow = 48 * hour
	// Больше этого сжатый отчёт прослойка не принимает (CHAN_REP_MAX_WIRE):
	// длиннее — окно уполовинивается, пока не влезет.
	reportMaxGz = 240 * 1024
)

const deviceFile = "device"

// deviceID — случайная метка установки: ею прослойка различает устройства,
// когда опознание устройства (x-hwid) выключено.
func deviceID(dir string) string {
	path := filepath.Join(dir, deviceFile)

	if raw, err := os.ReadFile(path); err == nil {
		text := strings.TrimSpace(string(raw))
		if _, err := hex.DecodeString(text); err == nil && len(text) == 32 {
			return text
		}
	}

	id := make([]byte, 16)
	if _, err := rand.Read(id); err != nil {
		return ""
	}

	text := hex.EncodeToString(id)

	_ = os.MkdirAll(dir, 0700)
	if err := os.WriteFile(path, []byte(text), 0600); err != nil {
		log.Warnln("[Report] the device mark was not saved: %s", err.Error())
	}

	return text
}

// Packed — сжатый отчёт, граница отправленного (часы раньше неё), сколько
// часов он охватывает и сколько в нём записей часов.
type Packed struct {
	Gz    []byte
	Until int64
	Span  int64
	Hours int
}

// SendHours — окно первой попытки отчёта, часов.
const SendHours = sendWindow / hour

func pack(report map[string]any) ([]byte, error) {
	raw, err := json.Marshal(report)
	if err != nil {
		return nil, err
	}

	var gz bytes.Buffer
	writer, _ := gzip.NewWriterLevel(&gz, gzip.BestCompression)
	if _, err := writer.Write(raw); err != nil {
		return nil, err
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}

	return gz.Bytes(), nil
}

// packWithin — окно от самого старого закрытого часа (раньше closed) на hours
// часов; не влезло в limit — окно уполовинивается. false — отправлять нечего.
func packWithin(store *Store, now, closed, hours int64, dev, client string, limit int, pack func(map[string]any) ([]byte, error)) (Packed, bool, error) {
	oldest, ok := store.OldestBefore(closed)
	if !ok {
		return Packed{}, false, nil
	}

	hours = max(hours, 1)

	for {
		until := min(oldest+hours*hour, closed)

		gz, err := pack(store.Report(now, until, dev, client))
		if err != nil {
			return Packed{}, false, err
		}

		span := (until - oldest) / hour
		if len(gz) <= limit || span <= 1 {
			return Packed{Gz: gz, Until: until, Span: span, Hours: store.HoursBefore(until)}, true, nil
		}

		hours = max(span/2, 1)
	}
}

// tooEarly — меньше 6 часов с прошлого ответа прослойки. Часы ушли назад
// (прошлый ответ «в будущем») — не рано.
func tooEarly(lastTry, now int64) bool {
	return lastTry <= now && now-lastTry < sendEvery
}

// Due — пора ли отправлять отчёт path, и граница закрытых часов: сборщик
// дочитывает окно, и в часы раньше границы уже ничего не ляжет.
func Due(path string, now int64) (int64, bool) {
	early := false

	withStore(path, now, false, func(store *Store) {
		store.Prune(now)
		early = tooEarly(store.LastTry, now)
	})

	if early {
		return 0, false
	}

	collectMu.Lock()

	mu.Lock()
	collecting := path == target
	mu.Unlock()

	var work ipWork
	if collecting {
		work = collectLocked()
	}

	closed := closedBefore(path, now)

	collectMu.Unlock()

	work.do()

	return closed, true
}

// Pack — отчёт к отправке из часов раньше closed, от самого старого на hours
// часов; false — отправлять нечего или не упаковалось.
func Pack(path string, now, closed, hours int64, client string) (Packed, bool) {
	var (
		packed Packed
		ready  bool
		err    error
	)

	withStore(path, now, false, func(store *Store) {
		packed, ready, err = packWithin(store, now, closed, hours, deviceID(filepath.Dir(path)), client, reportMaxGz, pack)
	})

	if err != nil {
		log.Warnln("[Report] the report was not packed: %s", err.Error())
	}

	return packed, ready && err == nil
}

// Sent — прослойка ответила каналом кодом status; until — граница
// отправленного. Принят — отправленное уходит; велик и один час (413) — он не
// уйдёт никогда и тоже уходит.
func Sent(path string, now, until int64, status int) {
	withStore(path, now, true, func(store *Store) {
		store.LastTry = now

		if status == 204 || status == 413 {
			store.DropSent(now, until)
		}
	})
}

// Outcome — что значит код ответа прослойки, словами для журнала.
func Outcome(status int) string {
	switch status {
	case 204:
		return "accepted"
	case 403:
		return "the middleware does not take reports"
	case 413:
		return "too big for the middleware even alone, dropped"
	case 429:
		return "too early for the middleware"
	default:
		return "the middleware does not know reports"
	}
}
