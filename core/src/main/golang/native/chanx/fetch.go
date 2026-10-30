package chanx

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"time"

	"cfa/native/config/panel"
)

var (
	errRedirects = errors.New("clod-chan: stopped after 10 redirects")
	errDowngrade = errors.New("clod-chan: refused redirect from https to plain http")
)

// ErrSilent помечает обмен, на который канал не ответил: сеть, таймаут, сбой
// 5xx по дороге. Добавление подписки в этом случае пробует канал заново.
var ErrSilent = errors.New("clod-chan-silent")

// CheckRedirect — переадресации запроса канала: не больше 10 и не с https на http.
func CheckRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return errRedirects
	}

	if via[0].URL.Scheme == "https" && req.URL.Scheme != "https" {
		return errDowngrade
	}

	return nil
}

func RefusedRedirect(err error) bool {
	return errors.Is(err, errDowngrade) || errors.Is(err, errRedirects)
}

// Silence оставляет как есть то, что сказал сам ответ (Spoke), и отказ
// переадресации, и помечает молчанием всё остальное.
func Silence(err error) error {
	if Spoke(err) || RefusedRedirect(err) {
		return err
	}

	return fmt.Errorf("%w: %w", ErrSilent, err)
}

// Over — один запрос по двум путям: через туннель, а не дошёл он до сервера
// или по дороге отказали (detour) — напрямую; прямой не дошёл до сервера —
// остаётся исход туннеля. attempt(direct) — запрос по пути: дошёл ли до сервера
// и исход. Возвращает исход и то, что он получен напрямую.
func Over(attempt func(direct bool) (bool, error), detour func(err error, reached bool) bool, warn func(string, ...any)) (bool, error) {
	reached, err := attempt(false)
	if errors.Is(err, ErrNoRound) || !detour(err, reached) || RefusedRedirect(err) {
		return false, err
	}

	directReached, directErr := attempt(true)
	if errors.Is(directErr, ErrNoRound) {
		warn("Secure channel: request failed through the tunnel (%s), no time left for a direct retry", err.Error())

		return false, err
	}

	warn("Secure channel: request failed through the tunnel (%s), retried directly", err.Error())

	if directErr != nil {
		warn("Secure channel: the direct retry failed as well (%s)", directErr.Error())
	}

	if directErr != nil && !directReached {
		return false, err
	}

	return true, directErr
}

// Way — путь запроса канала (через туннель или напрямую): клиент и срок
// попытки; refused — через туннель сервер достижим, но отказали или оборвали
// (прямому пути — не больше запаса); ok=false — на попытку не осталось времени.
type Way func(refused bool) (client *http.Client, ctx context.Context, ok bool)

// Fetch — подписка по каналу одним обменом (Exchange), каждый запрос — Over с
// GoDirect. Через туннель сервер недостижим, а напрямую ответил — следующие
// запросы обмена (поправка часов, без закреплённого ключа) сразу напрямую.
// Возвращает ответ и поправку часов, с которой он получен.
func Fetch(url string, pin []byte, offset int64, fields Fields, header http.Header, tunnel, direct Way, warn func(string, ...any)) (*Answer, int64, error) {
	directly := false

	return Exchange(pin, offset, func(pin []byte, offset int64) (*Answer, int64, error) {
		var answers [2]*Answer
		var served [2]int64
		var refused, tunnelReached bool

		attempt := func(viaDirect bool) (bool, error) {
			way, at := tunnel, 0
			if viaDirect {
				way, at = direct, 1
			}

			client, ctx, ok := way(refused)
			if !ok {
				return false, ErrNoRound
			}

			answer, seen, reached, err := ask(ctx, client, url, pin, offset, fields, header)
			answers[at], served[at] = answer, seen
			refused = reached && (errors.Is(err, ErrDoubt) || errors.Is(err, ErrDropped))
			if !viaDirect {
				tunnelReached = reached
			}

			return reached, err
		}

		if directly {
			_, err := attempt(true)

			return answers[1], served[1], err
		}

		viaDirect, err := Over(attempt, func(err error, reached bool) bool { return GoDirect(pin, err, reached) }, warn)
		if !viaDirect {
			return answers[0], served[0], err
		}

		// Через туннель сервер недостижим — дальше только напрямую. Достижим, но
		// отказали по дороге — каждый следующий запрос снова сперва через туннель
		directly = !tunnelReached

		return answers[1], served[1], err
	}, warn)
}

// ask — конверт, запрос и разбор ответа. Каждый запрос собирает свой конверт:
// у повтора своя метка, иначе прослойка, получившая первый, отбросила бы второй
// как повтор. reached — сервер ответил (пусть и не тем) или оборвал соединение
// после TLS.
func ask(ctx context.Context, client *http.Client, url string, pin []byte, offset int64, fields Fields, header http.Header) (*Answer, int64, bool, error) {
	secureURL, session, err := Build(url, pin, fields, time.Now().Unix()+offset)
	if err != nil {
		return nil, 0, true, err
	}

	ctx, droppedBy := Track(ctx)

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, secureURL, nil)
	if err != nil {
		return nil, 0, true, err
	}

	request.Header = header.Clone()

	response, err := client.Do(request)
	if err != nil {
		if dropped := droppedBy(err); dropped != nil {
			return nil, 0, true, dropped
		}

		return nil, 0, false, err
	}

	defer response.Body.Close()

	served := panel.ServerTime(response.Header)

	answer, err := session.Receive(response.StatusCode, response.Body, time.Now().Unix()+offset)

	return answer, served, true, err
}
