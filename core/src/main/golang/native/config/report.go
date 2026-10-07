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

// SendReport — после удачного планового обновления подписки: если подошло
// время, отчёт уходит POST'ом по защищённому каналу, адресом подписки, а не
// ответил он — запасным адресом провайдера. Ключ прослойки и поправка часов
// берутся из папки подписки и не меняются.
func SendReport(store, url, profileDir string) {
	now := time.Now().Unix()

	packed, ok := report.Prepare(store, now, app.VersionName())
	if !ok {
		return
	}

	status, err := postReport(url, profileDir, packed.Gz)
	if err != nil {
		if spare := readPanelInfo(profileDir).SpareAddress(url); spare != "" {
			status, err = postReport(spare, profileDir, packed.Gz)
		}
	}

	if err != nil {
		log.Infoln("[Report] the middleware did not answer over the secure channel, next try with the next scheduled update: %s", err.Error())

		return
	}

	report.Sent(store, now, packed.Until, status)

	log.Infoln("[Report] %d hour(s) of measurements sent: %s (%d)", packed.Hours, report.Outcome(status), status)
}

func postReport(url, profileDir string, gz []byte) (int, error) {
	pin := readChanPin(profileDir)
	offset := readChanSkew(profileDir)

	status, err := reportAttempt(url, pin, offset, gz)
	if err != nil && pin != nil && keyMayBeRefused(err) {
		status, err = reportAttempt(url, nil, offset, gz)
	}

	return status, err
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

	for _, pair := range chanBrowserHeaders {
		request.Header.Set(pair[0], pair[1])
	}
	request.Header.Set("content-type", "text/plain")

	response, err := chanClient(direct).Do(request)
	if err != nil {
		return 0, refusedByChanRedirect(err), err
	}
	defer response.Body.Close()

	answer, err := openAnswer(response, session, 1<<20, offset)
	if err != nil {
		return 0, true, err
	}

	return answer.Status, true, nil
}
