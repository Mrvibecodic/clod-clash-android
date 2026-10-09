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

// Packed — сжатый отчёт, граница отправленного (часы раньше неё) и число
// записей в нём.
type Packed struct {
	Gz    []byte
	Until int64
	Hours int
}

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

// packWithin — окно от самого старого закрытого часа на sendWindow, но не
// дальше текущего часа; не влезло в limit — окно уполовинивается.
func packWithin(store *Store, now, oldest int64, dev, client string, limit int, pack func(map[string]any) ([]byte, error)) (Packed, error) {
	current := hourOf(now)
	hours := int64(sendWindow / hour)

	for {
		until := min(oldest+hours*hour, current)

		gz, err := pack(store.Report(now, until, dev, client))
		if err != nil {
			return Packed{}, err
		}

		if len(gz) <= limit || hours <= 1 {
			return Packed{Gz: gz, Until: until, Hours: store.HoursBefore(until)}, nil
		}

		hours = max(hours/2, 1)
	}
}

// Prepare — отчёт к отправке; false — рано или отправлять нечего.
func Prepare(path string, now int64, client string) (Packed, bool) {
	var (
		packed Packed
		ready  bool
	)

	withStore(path, now, false, func(store *Store) {
		store.Prune(now)

		if now-store.LastTry < sendEvery {
			return
		}

		oldest, ok := store.OldestClosed(now)
		if !ok {
			return
		}

		var err error
		packed, err = packWithin(store, now, oldest, deviceID(filepath.Dir(path)), client, reportMaxGz, pack)
		if err != nil {
			log.Warnln("[Report] the report was not packed: %s", err.Error())

			return
		}

		ready = true
	})

	return packed, ready
}

// Sent — прослойка ответила каналом кодом status; until — граница отправленного.
func Sent(path string, now, until int64, status int) {
	withStore(path, now, true, func(store *Store) {
		store.LastTry = now

		if status == 204 {
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
	case 429:
		return "too early for the middleware"
	default:
		return "the middleware does not know reports"
	}
}
