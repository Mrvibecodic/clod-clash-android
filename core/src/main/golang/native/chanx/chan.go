package chanx

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

const (
	Version   = 1
	salt      = "clod-chan-v1"
	Skew      = 300
	maxAnswer = 32 << 20
	padBlock  = 512
	padKeyLen = 9

	// Тело отчёта выравнивается вместе с меткой до кратного этому: по длине
	// POST не видно, сколько в отчёте узлов и часов.
	reportPadBlock = 3072
	tagLen         = 16
	opReport       = "rep"
)

var b64 = base64.RawURLEncoding

var (
	// ErrBadAnswer — по адресу ответили не каналом (404, 410 или 2xx, который
	// не открылся): так прослойка отвечает на любой запрос, который не узнала,
	// — канала нет или закреплённый ключ ей неизвестен.
	ErrBadAnswer = errors.New("clod-chan-bad-answer")
	// ErrDoubt — иной код не из 2xx и не 5xx: его поставил посредник по
	// дороге (WAF, CDN, лимит) или сервер, отвечающий так на неизвестный
	// путь, — канал мог и быть.
	ErrDoubt = errors.New("clod-chan-doubt")
	// ErrMalformed — ответ расшифровался, значит, канал есть, но разобрать
	// его не вышло.
	ErrMalformed = errors.New("clod-chan-malformed")
	ErrTooLarge  = fmt.Errorf("response larger than %d bytes", maxAnswer)
	ErrStale     = errors.New("clod-chan-stale")
	ErrMismatch  = errors.New("clod-chan-mismatch")
	// ErrBadURL — адрес подписки не подходит для канала (нет пути с меткой,
	// не http): канала по такому адресу нет, подписка идёт обычным путём.
	ErrBadURL = errors.New("clod-chan-bad-url")
	// ErrNoPin — отчёт без закреплённого ключа прослойки не уходит.
	ErrNoPin = errors.New("clod-chan-no-pin")
	// ErrNoRound — на обмен не осталось времени; Exchange оставляет прежний исход.
	ErrNoRound = errors.New("clod-chan-no-round")

	errReportTooBig = errors.New("clod-chan-report-too-big")
)

func hkdf32(ikm []byte, salt, info string) []byte {
	out := make([]byte, 32)
	r := hkdf.New(sha256.New, ikm, []byte(salt), []byte(info))
	if _, err := r.Read(out); err != nil {
		panic(err)
	}
	return out
}

func Psk(token string) []byte {
	return hkdf32([]byte(token), salt, "psk")
}

func Epoch(now int64) int64 { return now / 86400 }

func Kid(psk []byte, epoch int64) string {
	mac := hmac.New(sha256.New, psk)
	mac.Write([]byte("kid|" + strconv.FormatInt(epoch, 10)))
	return b64.EncodeToString(mac.Sum(nil)[:9])
}

func Spid(publicKey []byte) string {
	sum := sha256.Sum256(publicKey)
	return b64.EncodeToString(sum[:])[:6]
}

type Fields struct {
	Hwid   string `json:"hwid,omitempty"`
	OS     string `json:"os,omitempty"`
	OSVer  string `json:"osv,omitempty"`
	Model  string `json:"model,omitempty"`
	UA     string `json:"ua,omitempty"`
	Accept string `json:"acc,omitempty"`
	Query  string `json:"q,omitempty"`
}

type request struct {
	V  int    `json:"v"`
	T  int64  `json:"t"`
	N  string `json:"n"`
	Op string `json:"op,omitempty"`
	Fields
}

type Session struct {
	psk    []byte
	kid    string
	dh     []byte
	ephPub []byte
	priv   []byte
	nonce  string
}

type Answer struct {
	Meta   map[string][]string
	Body   string
	Status int
	SP     []byte
}

func Build(base string, pinnedSP []byte, f Fields, now int64) (string, *Session, error) {
	return build(base, pinnedSP, f, "", now)
}

// BuildReport — адрес POST с отчётом: тот же конверт, что у запроса
// подписки, с операцией «rep»; тело запечатывает SealReport той же сессии.
// Только с закреплённым ключом прослойки: без него отчёт защищён одним
// адресом подписки.
func BuildReport(base string, pinnedSP []byte, f Fields, now int64) (string, *Session, error) {
	if len(pinnedSP) != 32 {
		return "", nil, ErrNoPin
	}

	return build(base, pinnedSP, f, opReport, now)
}

func build(base string, pinnedSP []byte, f Fields, op string, now int64) (string, *Session, error) {
	prefix, token, query, err := split(base)
	if err != nil {
		return "", nil, err
	}
	if query != "" && f.Query == "" {
		f.Query = query
	}

	psk := Psk(token)
	kid := Kid(psk, Epoch(now))

	priv := make([]byte, 32)
	if _, err := rand.Read(priv); err != nil {
		return "", nil, err
	}
	ephPub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		return "", nil, err
	}

	spid := "0"
	var dh []byte
	if len(pinnedSP) == 32 {
		spid = Spid(pinnedSP)
		if dh, err = curve25519.X25519(priv, pinnedSP); err != nil {
			return "", nil, err
		}
	}

	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", nil, err
	}
	nonce := b64.EncodeToString(raw)

	plain, err := json.Marshal(request{V: Version, T: now, N: nonce, Op: op, Fields: f})
	if err != nil {
		return "", nil, err
	}
	plain = pad(plain)

	aead, err := chacha20poly1305.New(hkdf32(concat(psk, dh), kid, "req"+string(ephPub)))
	if err != nil {
		return "", nil, err
	}
	cipher := aead.Seal(nil, make([]byte, chacha20poly1305.NonceSize), plain, []byte("c1"+kid+string(ephPub)))

	url := prefix + "/c1/" + kid + "/" + spid + "/" + b64.EncodeToString(concat(ephPub, cipher))

	return url, &Session{psk: psk, kid: kid, dh: dh, ephPub: ephPub, priv: priv, nonce: nonce}, nil
}

// reportFrame — длина сжатого отчёта (4 байта, big-endian), сам отчёт и нули
// до кратного reportPadBlock вместе с меткой.
func reportFrame(gz []byte) ([]byte, error) {
	if uint64(len(gz)) > uint64(^uint32(0)) {
		return nil, errReportTooBig
	}

	size := 4 + len(gz)
	need := (reportPadBlock - (size+tagLen)%reportPadBlock) % reportPadBlock

	frame := make([]byte, 4, size+need)
	binary.BigEndian.PutUint32(frame, uint32(len(gz)))
	frame = append(frame, gz...)

	return append(frame, make([]byte, need)...), nil
}

func (s *Session) reportKey() []byte {
	return hkdf32(concat(s.psk, s.dh), s.kid, opReport+string(s.ephPub))
}

// SealReport — тело POST: сжатый отчёт, запечатанный ключом отчёта, base64url
// без выравнивания.
func (s *Session) SealReport(gz []byte) (string, error) {
	frame, err := reportFrame(gz)
	if err != nil {
		return "", err
	}

	aead, err := chacha20poly1305.New(s.reportKey())
	if err != nil {
		return "", err
	}

	sealed := aead.Seal(nil, make([]byte, chacha20poly1305.NonceSize), frame, []byte("c1p"+s.kid+string(s.ephPub)))

	return b64.EncodeToString(sealed), nil
}

// Receive — ответ прослойки целиком. Канал отвечает всегда 200, остальное
// поставил кто-то другой: 5xx — сбой по дороге, молчание; 404 и 410 — ответ
// на любой неузнанный адрес, канала нет; иной код — посредник по дороге.
func (s *Session) Receive(status int, body io.Reader, now int64) (*Answer, error) {
	switch {
	case status >= 500:
		return nil, fmt.Errorf("server answered with status %d", status)
	case status == 404 || status == 410:
		return nil, fmt.Errorf("%w: relay answered with status %d", ErrBadAnswer, status)
	case status < 200 || status >= 300:
		return nil, fmt.Errorf("%w: relay answered with status %d", ErrDoubt, status)
	}

	wire, err := io.ReadAll(io.LimitReader(body, maxAnswer+1))
	if err != nil {
		return nil, err
	}
	if len(wire) > maxAnswer {
		return nil, ErrTooLarge
	}

	return s.Open(wire, now)
}

func (s *Session) Open(wire []byte, now int64) (*Answer, error) {
	body, err := b64.DecodeString(strings.TrimSpace(string(wire)))
	if err != nil || len(body) < 32+16 {
		return nil, ErrBadAnswer
	}

	sEph := body[:32]
	shared, err := curve25519.X25519(s.priv, sEph)
	if err != nil {
		return nil, ErrBadAnswer
	}

	aead, err := chacha20poly1305.New(hkdf32(concat(s.psk, shared, s.dh), s.kid, "res"+string(s.ephPub)))
	if err != nil {
		return nil, err
	}

	plain, err := aead.Open(nil, make([]byte, chacha20poly1305.NonceSize), body[32:],
		[]byte("c1r"+s.kid+string(s.ephPub)+string(sEph)))
	if err != nil {
		return nil, ErrBadAnswer
	}

	var answer struct {
		V       int                 `json:"v"`
		T       int64               `json:"t"`
		N       string              `json:"n"`
		St      int                 `json:"st"`
		SP      string              `json:"sp"`
		Meta    map[string][]string `json:"meta"`
		Body    string              `json:"body"`
		BodyB64 string              `json:"body_b64"`
	}
	if err := json.Unmarshal(plain, &answer); err != nil {
		return nil, ErrMalformed
	}

	if answer.V != Version {
		return nil, ErrMalformed
	}
	if !hmac.Equal([]byte(answer.N), []byte(s.nonce)) {
		return nil, ErrMismatch
	}
	if answer.T <= 0 || Abs(now-answer.T) > Skew {
		return nil, ErrStale
	}

	sp, err := b64.DecodeString(answer.SP)
	if err != nil || len(sp) != 32 {
		return nil, ErrMalformed
	}

	config := answer.Body
	if answer.BodyB64 != "" {
		raw, err := b64.DecodeString(answer.BodyB64)
		if err != nil {
			return nil, ErrMalformed
		}
		config = string(raw)
	}

	// Значение заголовка не в UTF-8 прослойка шлёт в base64 с этой приставкой.
	for _, values := range answer.Meta {
		for i, value := range values {
			if rest, ok := strings.CutPrefix(value, "=?b64?"); ok {
				if raw, err := b64.DecodeString(rest); err == nil {
					values[i] = string(raw)
				}
			}
		}
	}

	status := answer.St
	if status == 0 {
		status = 200
	}

	return &Answer{Meta: answer.Meta, Body: config, Status: status, SP: sp}, nil
}

func split(base string) (prefix, token, query string, err error) {
	rest := base
	if i := strings.IndexByte(rest, '#'); i >= 0 {
		rest = rest[:i]
	}
	if i := strings.IndexByte(rest, '?'); i >= 0 {
		query = rest[i+1:]
		rest = rest[:i]
	}
	rest = strings.TrimRight(rest, "/")

	i := strings.LastIndexByte(rest, '/')
	if i < 0 || i+1 >= len(rest) {
		return "", "", "", fmt.Errorf("%w: address without token: %s", ErrBadURL, base)
	}
	prefix, token = rest[:i], rest[i+1:]
	if !strings.HasPrefix(prefix, "http://") && !strings.HasPrefix(prefix, "https://") {
		return "", "", "", fmt.Errorf("%w: not an http address: %s", ErrBadURL, base)
	}

	return prefix, token, query, nil
}

func pad(plain []byte) []byte {
	size := len(plain)
	if size < 2 || size%padBlock == 0 {
		return plain
	}

	need := (padBlock - (size+padKeyLen)%padBlock) % padBlock

	out := make([]byte, 0, size+padKeyLen+need)
	out = append(out, plain[:size-1]...)
	out = append(out, `,"pad":"`...)
	for i := 0; i < need; i++ {
		out = append(out, '.')
	}

	return append(out, '"', '}')
}

func concat(parts ...[]byte) []byte {
	out := make([]byte, 0, 96)
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

func Abs(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

func Correction(served, now, current int64) (int64, bool) {
	if served <= 0 {
		return current, false
	}

	raw := served - now
	if Abs(raw) <= Skew {
		raw = 0
	}

	if raw == current {
		return current, false
	}

	return raw, true
}

// Spoke — исход сказал сам ответ (канала нет, посредник отказал, канал ответил
// не так, часы), а не молчание сети.
func Spoke(err error) bool {
	for _, known := range []error{ErrBadAnswer, ErrDoubt, ErrMalformed, ErrTooLarge, ErrStale, ErrMismatch, ErrBadURL} {
		if errors.Is(err, known) {
			return true
		}
	}

	return false
}

// Round — один обмен с прослойкой: ответ, время сервера из внешних заголовков
// (0 — его нет) и ошибка; ErrNoRound — на обмен не осталось времени.
type Round func(pin []byte, offset int64) (*Answer, int64, error)

// Exchange — обмены одного обновления подписки. Не вышло — ещё раз по часам
// прослойки, если часы устройства с ними разошлись. Без закрепления ключа
// повторяется только ответ «запрос не узнан» — любой внешний код не из 2xx и
// не 5xx (ErrBadAnswer, ErrDoubt) или страница вместо канала: так прослойка,
// потерявшая ключ, отдаёт запрос обычному конвейеру, и сервер за ней отвечает
// на неизвестный путь своим кодом. Молчание, сбой 5xx и ответ, который
// расшифровался, ключ не опровергают — снимать закрепление по ним нельзя.
// Возвращает ответ и поправку часов, с которой он получен.
func Exchange(pin []byte, offset int64, round Round, warn func(string, ...any)) (*Answer, int64, error) {
	answer, served, err := round(pin, offset)
	if err == nil || errors.Is(err, ErrNoRound) {
		return answer, offset, err
	}

	retry := func(pin []byte, what string) {
		next, _, nextErr := round(pin, offset)
		if errors.Is(nextErr, ErrNoRound) {
			warn("Secure channel: no time left for a round %s", what)

			return
		}

		answer, err = next, nextErr
	}

	if next, changed := Correction(served, time.Now().Unix(), offset); changed {
		if next == 0 {
			warn("Secure channel: the stored clock correction of %d s is stale, retrying with the device clock", offset)
		} else {
			warn("Secure channel: device clock is %d s off the relay, retrying with the relay time", next)
		}

		offset = next
		retry(pin, "with the relay time")
	}

	if err != nil && pin != nil && (errors.Is(err, ErrBadAnswer) || errors.Is(err, ErrDoubt)) {
		warn("Secure channel: pinned relay key refused (%v), retrying without the pin", err)

		retry(nil, "without the pin")

		if err == nil && !bytes.Equal(answer.SP, pin) {
			warn("Secure channel: the relay key changed from %s to %s", Spid(pin), Spid(answer.SP))
		}
	}

	return answer, offset, err
}
