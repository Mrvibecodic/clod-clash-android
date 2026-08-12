// Защищённый канал клиент ↔ прослойка (протокол c1), клиентская половина.
//
// Секрет — сам токен подписки: в сеть он не уходит никогда, из него выводятся
// ключи. Посредник, терминирующий TLS, видит только путь вида
// /c1/<kid>/<spid>/<blob>, где kid меняется каждые сутки.
//
// Набор примитивов один и не согласуется: X25519 + HKDF-SHA256 +
// ChaCha20-Poly1305. Всё из golang.org/x/crypto, который уже есть в дереве
// ядра, — новых модулей ноль.
package chanx

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
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
	skew      = 300
	maxAnswer = 32 << 20
	// Запрос дополняется до кратного этому размеру. Без выравнивания длина
	// адреса выдаёт длину карточки устройства: модель телефона, версию системы
	// и сам момент, когда они поменялись, — то есть ровно то, что канал прячет.
	padBlock = 512
	// `,"pad":""` — столько занимает сам ключ в JSON. Дополнить короче нечем,
	// поэтому если до кратности осталось меньше, добирается целый блок.
	padKeyLen = 9
)

var b64 = base64.RawURLEncoding

var (
	ErrBadAnswer = errors.New("clod-chan-bad-answer")
	ErrStale     = errors.New("clod-chan-stale")
	ErrMismatch  = errors.New("clod-chan-mismatch")
)

func hkdf32(ikm []byte, salt, info string) []byte {
	out := make([]byte, 32)
	r := hkdf.New(sha256.New, ikm, []byte(salt), []byte(info))
	if _, err := r.Read(out); err != nil {
		panic(err) // HKDF на 32 байтах не может не прочитаться
	}
	return out
}

// Psk — ключ подписки, выведенный из её адреса.
func Psk(token string) []byte {
	return hkdf32([]byte(token), salt, "psk")
}

func Epoch(now int64) int64 { return now / 86400 }

// Kid — метка подписки на сутки.
func Kid(psk []byte, epoch int64) string {
	mac := hmac.New(sha256.New, psk)
	mac.Write([]byte("kid|" + strconv.FormatInt(epoch, 10)))
	return b64.EncodeToString(mac.Sum(nil)[:9])
}

// Spid — короткий отпечаток ключа прослойки.
func Spid(publicKey []byte) string {
	sum := sha256.Sum256(publicKey)
	return b64.EncodeToString(sum[:])[:6]
}

// Fields — то, что раньше ехало заголовками запроса открытым текстом.
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
	V int    `json:"v"`
	T int64  `json:"t"`
	N string `json:"n"`
	Fields
}

// Session — состояние одного обмена. Живёт от сборки запроса до разбора ответа.
type Session struct {
	psk    []byte
	kid    string
	dh     []byte
	ephPub []byte
	priv   []byte
	nonce  string
}

// Answer — то, что приехало внутри шифра.
type Answer struct {
	Meta map[string][]string
	Body string
	// Status — код ответа, который в открытом режиме приехал бы снаружи.
	// Снаружи на защищённом пути всегда 200: иначе посредник читал бы по коду,
	// чем кончилось дело, — 404 у неизвестной подписки, 502 при обрыве.
	Status int
	// SP — текущий ключ прослойки. Клиент закрепляет его при первом успехе
	// и дальше считает с ним DH: это даёт совершенную прямую секретность
	// и страхует от короткого токена.
	SP []byte
}

// Build собирает адрес защищённого запроса.
//
// base — адрес подписки как его ввёл человек; из него берётся всё, кроме
// последнего сегмента пути: последний сегмент и есть токен, и он остаётся
// на устройстве.
func Build(base string, pinnedSP []byte, f Fields, now int64) (string, *Session, error) {
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

	plain, err := json.Marshal(request{V: Version, T: now, N: nonce, Fields: f})
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

// Open разбирает ответ прослойки.
//
// wire — тело ответа как оно пришло: base64url без выравнивания. Двоичного
// тела на проводе нет сознательно, см. комментарий в chan.php.
func (s *Session) Open(wire []byte, now int64) (*Answer, error) {
	if len(wire) > maxAnswer {
		return nil, ErrBadAnswer
	}

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
		V    int                 `json:"v"`
		T    int64               `json:"t"`
		N    string              `json:"n"`
		St   int                 `json:"st"`
		SP   string              `json:"sp"`
		Meta map[string][]string `json:"meta"`
		Body string              `json:"body"`
		// Тело подписки прослойка отдаёт байт в байт, а JSON так не умеет:
		// одного байта не в UTF-8 хватает, чтобы кодирование не состоялось.
		// В этом случае тело приезжает сюда.
		BodyB64 string `json:"body_b64"`
	}
	if err := json.Unmarshal(plain, &answer); err != nil {
		return nil, ErrBadAnswer
	}

	if answer.V != Version {
		return nil, ErrBadAnswer
	}
	// Эхо метки запроса: ответ обязан быть ответом именно на наш запрос,
	// а не записанным когда-то раньше.
	if !hmac.Equal([]byte(answer.N), []byte(s.nonce)) {
		return nil, ErrMismatch
	}
	if answer.T <= 0 || abs(now-answer.T) > skew {
		return nil, ErrStale
	}

	sp, err := b64.DecodeString(answer.SP)
	if err != nil || len(sp) != 32 {
		return nil, ErrBadAnswer
	}

	config := answer.Body
	if answer.BodyB64 != "" {
		raw, err := b64.DecodeString(answer.BodyB64)
		if err != nil {
			return nil, ErrBadAnswer
		}
		config = string(raw)
	}

	status := answer.St
	if status == 0 {
		status = 200
	}

	return &Answer{Meta: answer.Meta, Body: config, Status: status, SP: sp}, nil
}

// split делит адрес подписки на префикс и токен.
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
		return "", "", "", fmt.Errorf("clod-chan: адрес без токена: %s", base)
	}
	prefix, token = rest[:i], rest[i+1:]
	if !strings.HasPrefix(prefix, "http://") && !strings.HasPrefix(prefix, "https://") {
		return "", "", "", fmt.Errorf("clod-chan: не http-адрес: %s", base)
	}

	return prefix, token, query, nil
}

// pad дополняет открытый текст запроса до кратного padBlock.
//
// Поле дописывается в уже собранный JSON, а не в структуру: так порядок полей
// остаётся тем же, что в тестовых векторах, и не зависит от сериализатора.
// Прослойка это поле не читает вовсе — выравнивание нужно только на проводе.
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

func abs(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

// Now — время в секундах; вынесено ради тестов.
func Now() int64 { return time.Now().Unix() }
