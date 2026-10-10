package config

import (
	"context"
	"net/http"
	"strings"
	"time"

	"cfa/native/app"
	"cfa/native/chanx"
	"cfa/native/report"

	"github.com/metacubex/mihomo/log"
)

const reportRound = 20 * time.Second

// SendReport — после удачного планового обновления подписки и между редкими
// обновлениями: если подошло время, отчёт уходит POST'ом по защищённому
// каналу, адресом подписки, а не ответил он — запасным адресом провайдера.
// Велик для прослойки (413) — тут же уходит вдвое меньшее окно.
// Ключ прослойки и поправка часов берутся из папки подписки и не меняются;
// без закреплённого ключа отчёт ждёт следующей отправки.
func SendReport(store, url, profileDir string) {
	pin := readChanPin(profileDir)
	if pin == nil {
		log.Infoln("[Report] no relay key pinned yet, next try with the next send")

		return
	}

	now := time.Now().Unix()

	closed, due := report.Due(store, now)
	if !due {
		return
	}

	offset := readChanSkew(profileDir)
	spare := readPanelInfo(profileDir).SpareAddress(url)

	for hours := int64(report.SendHours); ; {
		packed, ok := report.Pack(store, now, closed, hours, app.VersionName())
		if !ok {
			return
		}

		status, err := reportAttempt(url, pin, offset, packed.Gz)
		if err != nil && spare != "" {
			status, err = reportAttempt(spare, pin, offset, packed.Gz)
		}

		if err != nil {
			log.Infoln("[Report] the middleware did not answer over the secure channel, next try with the next send: %s", err.Error())

			return
		}

		// Велик — тут же вдвое меньшее окно.
		if status == 413 && packed.Span > 1 {
			hours = packed.Span / 2

			continue
		}

		// Час, не влезший и один, отброшен ниже — дальше снова полное окно.
		if status == 413 {
			hours = report.SendHours
		}

		report.Sent(store, now, packed.Until, status)

		log.Infoln("[Report] %d hour(s) of measurements sent: %s (%d)", packed.Hours, report.Outcome(status), status)

		if status != 413 {
			return
		}
	}
}

// Одна попытка: через туннель, а если он не донёс — напрямую. Каждый запрос
// собирает свой конверт: у повтора своя метка, иначе прослойка, получившая
// первый, отбросила бы второй как повтор.
func reportAttempt(url string, pin []byte, offset int64, gz []byte) (int, error) {
	status, reached, err := reportSend(url, pin, offset, gz, false)
	if err != nil && !reached {
		status, _, err = reportSend(url, pin, offset, gz, true)
	}

	return status, err
}

// reached — прослойка (или кто-то вместо неё) ответила: повторять напрямую незачем.
func reportSend(url string, pin []byte, offset int64, gz []byte, direct bool) (int, bool, error) {
	secureURL, session, err := chanx.BuildReport(url, pin, chanFields(), time.Now().Unix()+offset)
	if err != nil {
		return 0, true, err
	}

	body, err := session.SealReport(gz)
	if err != nil {
		return 0, true, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), reportRound)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, secureURL, strings.NewReader(body))
	if err != nil {
		return 0, true, err
	}

	request.Header = chanHeaders()
	request.Header.Set("content-type", "text/plain")

	response, err := chanClient(direct).Do(request)
	if err != nil {
		return 0, refusedByChanRedirect(err), err
	}
	defer response.Body.Close()

	answer, err := session.Receive(response.StatusCode, response.Body, time.Now().Unix()+offset)
	if err != nil {
		return 0, true, err
	}

	return answer.Status, true, nil
}
