package chanx

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
)

type vectors struct {
	Token       string `json:"token"`
	Psk         string `json:"psk"`
	Epoch       int64  `json:"epoch"`
	Kid         string `json:"kid"`
	SpPublic    string `json:"sp_public"`
	Spid        string `json:"spid"`
	EphSecret   string `json:"eph_secret"`
	EphPublic   string `json:"eph_public"`
	Dh          string `json:"dh"`
	ReqPadBlock int    `json:"req_pad_block"`
	NonceLen    int    `json:"nonce_len"`
	Request     struct {
		Plain      string `json:"plain"`
		KeyPinned  string `json:"key_pinned"`
		BlobPinned string `json:"blob_pinned"`
		KeyFirst   string `json:"key_first"`
		BlobFirst  string `json:"blob_first"`
		PathPinned string `json:"path_pinned"`
	} `json:"request"`
	Report struct {
		PadBlock int    `json:"pad_block"`
		Plain    string `json:"plain"`
		Blob     string `json:"blob"`
		Path     string `json:"path"`
		Key      string `json:"key"`
		JSON     string `json:"json"`
		Gzip     string `json:"gzip"`
		FrameLen int    `json:"frame_len"`
		Body     string `json:"body"`
	} `json:"report"`
	Response struct {
		SrvSecret  string `json:"srv_secret"`
		Body       string `json:"body"`
		BodyBinary string `json:"body_binary"`
		Expect     struct {
			MetaAnnounce string `json:"meta_announce"`
			Config       string `json:"config"`
			ConfigBinary string `json:"config_binary"`
			Nonce        string `json:"nonce"`
			St           int    `json:"st"`
		} `json:"expect"`
	} `json:"response"`
}

func load(t *testing.T) vectors {
	t.Helper()
	raw, err := os.ReadFile("vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v vectors
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestDerivation(t *testing.T) {
	v := load(t)

	if got := hex.EncodeToString(Psk(v.Token)); got != v.Psk {
		t.Fatalf("psk: %s != %s", got, v.Psk)
	}
	if got := Kid(Psk(v.Token), v.Epoch); got != v.Kid {
		t.Fatalf("kid: %s != %s", got, v.Kid)
	}
	if got := Spid(unhex(t, v.SpPublic)); got != v.Spid {
		t.Fatalf("spid: %s != %s", got, v.Spid)
	}

	shared, err := curve25519.X25519(unhex(t, v.EphSecret), unhex(t, v.SpPublic))
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(shared); got != v.Dh {
		t.Fatalf("dh: %s != %s", got, v.Dh)
	}
}

func TestRequestKeysAndBlob(t *testing.T) {
	v := load(t)
	psk, ephPub, dh := Psk(v.Token), unhex(t, v.EphPublic), unhex(t, v.Dh)

	first := hkdf32(psk, v.Kid, "req"+string(ephPub))
	if got := hex.EncodeToString(first); got != v.Request.KeyFirst {
		t.Fatalf("ключ первого контакта: %s != %s", got, v.Request.KeyFirst)
	}

	pinned := hkdf32(concat(psk, dh), v.Kid, "req"+string(ephPub))
	if got := hex.EncodeToString(pinned); got != v.Request.KeyPinned {
		t.Fatalf("ключ с закреплённым: %s != %s", got, v.Request.KeyPinned)
	}

	aead, err := chacha20poly1305.New(pinned)
	if err != nil {
		t.Fatal(err)
	}
	sealed := aead.Seal(nil, make([]byte, 12), []byte(v.Request.Plain), []byte("c1"+v.Kid+string(ephPub)))
	if got := b64.EncodeToString(concat(ephPub, sealed)); got != v.Request.BlobPinned {
		t.Fatalf("blob: %s != %s", got, v.Request.BlobPinned)
	}
}

func TestResponse(t *testing.T) {
	v := load(t)

	sess := &Session{
		psk:    Psk(v.Token),
		kid:    v.Kid,
		dh:     unhex(t, v.Dh),
		ephPub: unhex(t, v.EphPublic),
		priv:   unhex(t, v.EphSecret),
		nonce:  v.Response.Expect.Nonce,
	}

	body := []byte(v.Response.Body)
	raw, err := b64.DecodeString(v.Response.Body)
	if err != nil {
		t.Fatal(err)
	}

	answer, err := sess.Open(body, 0)
	if err != ErrStale {
		t.Fatalf("ожидали отказ по метке времени, получили %v", err)
	}

	var probe struct {
		T int64 `json:"t"`
	}
	if answer == nil {
		sEph := raw[:32]
		shared, _ := curve25519.X25519(sess.priv, sEph)
		aead, _ := chacha20poly1305.New(hkdf32(concat(sess.psk, shared, sess.dh), sess.kid, "res"+string(sess.ephPub)))
		plain, err := aead.Open(nil, make([]byte, 12), raw[32:], []byte("c1r"+sess.kid+string(sess.ephPub)+string(sEph)))
		if err != nil {
			t.Fatalf("ответ не расшифровался: %v", err)
		}
		if err := json.Unmarshal(plain, &probe); err != nil {
			t.Fatal(err)
		}
	}

	answer, err = sess.Open(body, probe.T)
	if err != nil {
		t.Fatalf("разбор ответа: %v", err)
	}
	if got := answer.Meta["announce"]; len(got) != 1 || got[0] != v.Response.Expect.MetaAnnounce {
		t.Fatalf("announce: %v", got)
	}
	if answer.Body != v.Response.Expect.Config {
		t.Fatalf("тело: %q", answer.Body)
	}
	if answer.Status != v.Response.Expect.St {
		t.Fatalf("код ответа: %d != %d", answer.Status, v.Response.Expect.St)
	}
	if hex.EncodeToString(answer.SP) != v.SpPublic {
		t.Fatalf("ключ прослойки не тот")
	}
}

func TestAnswerBoundToRequest(t *testing.T) {
	v := load(t)
	body := []byte(v.Response.Body)

	sess := &Session{
		psk:    Psk(v.Token),
		kid:    v.Kid,
		dh:     unhex(t, v.Dh),
		ephPub: unhex(t, v.EphPublic),
		priv:   unhex(t, v.EphSecret),
		nonce:  "чужая-метка-запроса",
	}

	if _, err := sess.Open(body, 1786500000); err != ErrMismatch {
		t.Fatalf("ответ на чужой запрос обязан отбиваться, получили %v", err)
	}
}

func TestSplit(t *testing.T) {
	cases := map[string][2]string{
		"https://sub.dom/abc":          {"https://sub.dom", "abc"},
		"https://sub.dom/sub/abc/":     {"https://sub.dom/sub", "abc"},
		"https://sub.dom/abc?fmt=yaml": {"https://sub.dom", "abc"},
		"https://sub.dom/abc#c":        {"https://sub.dom", "abc"},
	}
	for in, want := range cases {
		prefix, token, _, err := split(in)
		if err != nil {
			t.Fatalf("%s: %v", in, err)
		}
		if prefix != want[0] || token != want[1] {
			t.Fatalf("%s → %s | %s", in, prefix, token)
		}
	}
	// Адрес без пути с меткой (в том числе с меткой в query) — ErrBadURL: по
	// нему канала не бывает, клиент идёт обычным путём, а не ждёт ответа.
	for _, in := range []string{"https://sub.dom/", "https://sub.dom/?token=abc", "ftp://sub.dom/x"} {
		if _, _, _, err := split(in); !errors.Is(err, ErrBadURL) {
			t.Fatalf("%s: %v", in, err)
		}
	}
}

func TestBinaryBody(t *testing.T) {
	v := load(t)

	sess := &Session{
		psk:    Psk(v.Token),
		kid:    v.Kid,
		dh:     unhex(t, v.Dh),
		ephPub: unhex(t, v.EphPublic),
		priv:   unhex(t, v.EphSecret),
		nonce:  v.Response.Expect.Nonce,
	}

	answer, err := sess.Open([]byte(v.Response.BodyBinary), 1786500000)
	if err != nil {
		t.Fatalf("разбор ответа: %v", err)
	}

	want, err := base64.StdEncoding.DecodeString(v.Response.Expect.ConfigBinary)
	if err != nil {
		t.Fatal(err)
	}
	if answer.Body != string(want) {
		t.Fatalf("двоичное тело: %q != %q", answer.Body, string(want))
	}
}

func TestRequestIsPadded(t *testing.T) {
	v := load(t)

	if len(v.Request.Plain)%v.ReqPadBlock != 0 {
		t.Fatalf("вектор запроса не выровнен: %d", len(v.Request.Plain))
	}

	short, _, err := Build("https://sub.dom/"+v.Token, nil, Fields{Hwid: "a"}, 1786500000)
	if err != nil {
		t.Fatal(err)
	}
	long, _, err := Build("https://sub.dom/"+v.Token, nil, Fields{
		Hwid:  "3f9c1d2e-aaaa-bbbb-cccc-ddddddddddddd",
		OS:    "android",
		OSVer: "15",
		Model: "Pixel 8 Pro (полное имя устройства)",
		UA:    "ClodClash/0.0.10 (Android)",
	}, 1786500000)
	if err != nil {
		t.Fatal(err)
	}
	if len(short) != len(long) {
		t.Fatalf("длина адреса выдаёт карточку устройства: %d != %d", len(short), len(long))
	}
}

func TestNonceLength(t *testing.T) {
	v := load(t)

	_, sess, err := Build("https://sub.dom/"+v.Token, nil, Fields{}, 1786500000)
	if err != nil {
		t.Fatal(err)
	}
	if len(sess.nonce) != v.NonceLen {
		t.Fatalf("длина метки: %d != %d", len(sess.nonce), v.NonceLen)
	}
}

func TestCorrection(t *testing.T) {
	for _, row := range []struct {
		name    string
		served  int64
		now     int64
		current int64
		want    int64
		changed bool
	}{
		{"no relay time at all", 0, 1000, 0, 0, false},
		{"no relay time, correction already stored", 0, 1000, 3600, 3600, false},
		{"clocks agree", 1000, 1000, 0, 0, false},
		{"divergence exactly at the tolerance", 1000 + Skew, 1000, 0, 0, false},
		{"divergence just past the tolerance", 1000 + Skew + 1, 1000, 0, Skew + 1, true},
		{"device clock an hour behind", 4600, 1000, 0, 3600, true},
		{"device clock an hour ahead", 1000, 4600, 0, -3600, true},
		{"stored correction is still right", 4600, 1000, 3600, 3600, false},
		{"stored correction went stale", 1000, 1000, 3600, 0, true},
		{"stored correction needs adjusting", 4600, 1000, 7200, 3600, true},
	} {
		got, changed := Correction(row.served, row.now, row.current)

		if got != row.want || changed != row.changed {
			t.Fatalf("%s: Correction(%d, %d, %d) = %d, %v; want %d, %v",
				row.name, row.served, row.now, row.current, got, changed, row.want, row.changed)
		}
	}
}

func TestReportMatchesVectors(t *testing.T) {
	v := load(t)
	r := v.Report
	psk, ephPub, dh := Psk(v.Token), unhex(t, v.EphPublic), unhex(t, v.Dh)

	var envelope map[string]any
	if err := json.Unmarshal([]byte(r.Plain), &envelope); err != nil {
		t.Fatal(err)
	}
	plain, err := json.Marshal(request{
		V:      Version,
		T:      int64(envelope["t"].(float64)),
		N:      envelope["n"].(string),
		Op:     opReport,
		Fields: Fields{Hwid: envelope["hwid"].(string), OS: envelope["os"].(string)},
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := string(pad(plain)); got != r.Plain {
		t.Fatalf("конверт отчёта:\n%s\n%s", got, r.Plain)
	}

	aead, err := chacha20poly1305.New(hkdf32(concat(psk, dh), v.Kid, "req"+string(ephPub)))
	if err != nil {
		t.Fatal(err)
	}
	sealed := aead.Seal(nil, make([]byte, 12), []byte(r.Plain), []byte("c1"+v.Kid+string(ephPub)))
	if got := b64.EncodeToString(concat(ephPub, sealed)); got != r.Blob {
		t.Fatalf("blob отчёта: %s != %s", got, r.Blob)
	}
	if got := "/c1/" + v.Kid + "/" + v.Spid + "/" + r.Blob; got != r.Path {
		t.Fatalf("путь отчёта: %s != %s", got, r.Path)
	}

	session := &Session{psk: psk, kid: v.Kid, dh: dh, ephPub: ephPub}
	if got := hex.EncodeToString(session.reportKey()); got != r.Key {
		t.Fatalf("ключ отчёта: %s != %s", got, r.Key)
	}

	gz, err := base64.StdEncoding.DecodeString(r.Gzip)
	if err != nil {
		t.Fatal(err)
	}
	frame, err := reportFrame(gz)
	if err != nil {
		t.Fatal(err)
	}
	if len(frame) != r.FrameLen || (len(frame)+tagLen)%r.PadBlock != 0 {
		t.Fatalf("рамка отчёта: %d, ждали %d", len(frame), r.FrameLen)
	}
	body, err := session.SealReport(gz)
	if err != nil {
		t.Fatal(err)
	}
	if body != r.Body {
		t.Fatal("тело отчёта не совпало с вектором")
	}
}

func TestReportBodyHidesTheSize(t *testing.T) {
	v := load(t)
	session := &Session{psk: Psk(v.Token), kid: v.Kid, ephPub: unhex(t, v.EphPublic)}

	small, err := session.SealReport(make([]byte, 10))
	if err != nil {
		t.Fatal(err)
	}
	bigger, err := session.SealReport(make([]byte, 3000))
	if err != nil {
		t.Fatal(err)
	}
	if len(small) != len(bigger) || len(small) != 4096 {
		t.Fatalf("длина тела выдаёт размер отчёта: %d и %d", len(small), len(bigger))
	}
}

func TestSubscriptionRequestCarriesNoOperation(t *testing.T) {
	plain, err := json.Marshal(request{V: Version, T: 1, N: "x", Fields: Fields{Hwid: "a"}})
	if err != nil {
		t.Fatal(err)
	}
	if string(plain) != `{"v":1,"t":1,"n":"x","hwid":"a"}` {
		t.Fatalf("у запроса подписки лишнее поле: %s", plain)
	}
}

func session(t *testing.T, v vectors) *Session {
	t.Helper()

	return &Session{
		psk:    Psk(v.Token),
		kid:    v.Kid,
		dh:     unhex(t, v.Dh),
		ephPub: unhex(t, v.EphPublic),
		priv:   unhex(t, v.EphSecret),
		nonce:  v.Response.Expect.Nonce,
	}
}

// seal — ответ прослойки на эту сессию, как его собирает chan_seal: свой
// ключ ответа из srv_secret векторов, полезная нагрузка — любая.
func seal(t *testing.T, v vectors, sess *Session, payload map[string]any) []byte {
	t.Helper()

	secret := unhex(t, v.Response.SrvSecret)
	public, err := curve25519.X25519(secret, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	shared, err := curve25519.X25519(secret, sess.ephPub)
	if err != nil {
		t.Fatal(err)
	}

	plain, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}

	aead, err := chacha20poly1305.New(hkdf32(concat(sess.psk, shared, sess.dh), sess.kid, "res"+string(sess.ephPub)))
	if err != nil {
		t.Fatal(err)
	}
	cipher := aead.Seal(nil, make([]byte, 12), plain, []byte("c1r"+sess.kid+string(sess.ephPub)+string(public)))

	return []byte(b64.EncodeToString(concat(public, cipher)))
}

func payload(t *testing.T, v vectors, now int64) map[string]any {
	return map[string]any{
		"v":    Version,
		"t":    now,
		"n":    v.Response.Expect.Nonce,
		"st":   200,
		"sp":   b64.EncodeToString(unhex(t, v.SpPublic)),
		"meta": map[string][]string{},
		"body": "proxies: []\n",
	}
}

func TestSealHelperMatchesTheChannel(t *testing.T) {
	v := load(t)
	sess := session(t, v)
	const now = 1786500000

	answer, err := sess.Open(seal(t, v, sess, payload(t, v, now)), now)
	if err != nil || answer.Body != "proxies: []\n" {
		t.Fatalf("собранный тестом ответ не открылся: %v", err)
	}
}

// Ответ расшифровался — канал у провайдера есть: это ошибка ответа, а не
// «канала нет», иначе подписка ушла бы на обычный путь.
func TestDecryptedButMalformedIsNotAbsent(t *testing.T) {
	v := load(t)
	sess := session(t, v)
	const now = 1786500000

	for name, change := range map[string]func(map[string]any){
		"unknown version": func(p map[string]any) { p["v"] = 2 },
		"no relay key":    func(p map[string]any) { p["sp"] = "" },
		"bad body_b64":    func(p map[string]any) { p["body_b64"] = "!!" },
	} {
		p := payload(t, v, now)
		change(p)

		_, err := sess.Open(seal(t, v, sess, p), now)
		if !errors.Is(err, ErrMalformed) || errors.Is(err, ErrBadAnswer) {
			t.Fatalf("%s: %v", name, err)
		}
		if !Spoke(err) {
			t.Fatalf("%s: разобранный отказ не молчание", name)
		}
	}
}

func TestMetaInBase64IsDecoded(t *testing.T) {
	v := load(t)
	sess := session(t, v)
	const now = 1786500000

	p := payload(t, v, now)
	p["meta"] = map[string][]string{
		"profile-title": {"=?b64?" + b64.EncodeToString([]byte("Name \xff"))},
		"announce":      {"plain"},
	}

	answer, err := sess.Open(seal(t, v, sess, p), now)
	if err != nil {
		t.Fatal(err)
	}
	if got := answer.Meta["profile-title"]; len(got) != 1 || got[0] != "Name \xff" {
		t.Fatalf("profile-title: %q", got)
	}
	if got := answer.Meta["announce"]; len(got) != 1 || got[0] != "plain" {
		t.Fatalf("announce: %q", got)
	}
}

type endless struct{}

func (endless) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 'A'
	}

	return len(p), nil
}

func TestReceiveClassifiesTheOuterAnswer(t *testing.T) {
	v := load(t)
	sess := session(t, v)

	for _, row := range []struct {
		status int
		body   io.Reader
		absent bool
		doubt  bool
		spoke  bool
	}{
		{404, strings.NewReader("<html>"), true, false, true},
		{410, strings.NewReader(""), true, false, true},
		{200, strings.NewReader("<html>not a channel</html>"), true, false, true},
		{200, strings.NewReader(""), true, false, true},
		{403, strings.NewReader("<html>waf</html>"), false, true, true},
		{429, strings.NewReader(""), false, true, true},
		{302, strings.NewReader(""), false, true, true},
		{502, strings.NewReader("bad gateway"), false, false, false},
	} {
		_, err := sess.Receive(row.status, row.body, 1786500000)
		if errors.Is(err, ErrBadAnswer) != row.absent || errors.Is(err, ErrDoubt) != row.doubt || Spoke(err) != row.spoke {
			t.Fatalf("%d: %v", row.status, err)
		}
	}

	if _, err := sess.Receive(200, io.LimitReader(endless{}, maxAnswer+1), 1786500000); !errors.Is(err, ErrTooLarge) || !Spoke(err) {
		t.Fatalf("длинный ответ не обрезается молча: %v", err)
	}
	if _, err := sess.Receive(200, io.LimitReader(endless{}, maxAnswer), 1786500000); errors.Is(err, ErrTooLarge) {
		t.Fatalf("ответ ровно в предел — не слишком большой: %v", err)
	}
	if !strings.HasPrefix(ErrTooLarge.Error(), "response larger than") {
		t.Fatalf("метка слишком большого ответа: %v", ErrTooLarge)
	}
}

func TestReportNeedsAPinnedKey(t *testing.T) {
	v := load(t)

	if _, _, err := BuildReport("https://sub.dom/"+v.Token, nil, Fields{}, 1786500000); !errors.Is(err, ErrNoPin) {
		t.Fatalf("отчёт без закреплённого ключа: %v", err)
	}
	if _, _, err := BuildReport("https://sub.dom/"+v.Token, unhex(t, v.SpPublic), Fields{}, 1786500000); err != nil {
		t.Fatal(err)
	}
}

type call struct {
	pin    []byte
	offset int64
}

// rounds — поддельная прослойка: i-й обмен отдаёт i-й исход и запоминает,
// с каким ключом и поправкой его просили.
func rounds(outcomes ...func(pin []byte) (*Answer, int64, error)) (Round, *[]call) {
	var calls []call

	return func(pin []byte, offset int64) (*Answer, int64, error) {
		calls = append(calls, call{pin, offset})
		if len(calls) > len(outcomes) {
			return nil, 0, ErrNoRound
		}

		return outcomes[len(calls)-1](pin)
	}, &calls
}

func fails(err error, served int64) func([]byte) (*Answer, int64, error) {
	return func([]byte) (*Answer, int64, error) { return nil, served, err }
}

func answers(sp []byte) func([]byte) (*Answer, int64, error) {
	return func([]byte) (*Answer, int64, error) { return &Answer{SP: sp, Status: 200}, 0, nil }
}

func key(b byte) []byte {
	out := make([]byte, 32)
	out[0] = b

	return out
}

func TestExchangeDropsThePinOnlyWhenTheRequestIsNotRecognised(t *testing.T) {
	pinned, fresh := key(1), key(2)

	var warned []string
	warn := func(format string, args ...any) { warned = append(warned, fmt.Sprintf(format, args...)) }

	// Прослойка, потерявшая ключ, отдаёт запрос обычному конвейеру: 404, 410,
	// другой код не из 2xx и не 5xx или страница подписки с кодом 200 —
	// повтор без закрепления.
	for name, refusal := range map[string]error{
		"404":  fmt.Errorf("%w: relay answered with status 404", ErrBadAnswer),
		"410":  fmt.Errorf("%w: relay answered with status 410", ErrBadAnswer),
		"400":  fmt.Errorf("%w: relay answered with status 400", ErrDoubt),
		"403":  fmt.Errorf("%w: relay answered with status 403", ErrDoubt),
		"page": ErrBadAnswer,
	} {
		warned = nil
		round, calls := rounds(fails(refusal, 0), answers(fresh))
		answer, _, err := Exchange(pinned, 0, round, warn)
		if err != nil || len(*calls) != 2 || (*calls)[1].pin != nil || string(answer.SP) != string(fresh) {
			t.Fatalf("%s: прослойка, не узнавшая ключ, — повтор без закрепления: %v %+v", name, err, *calls)
		}
		if !strings.Contains(strings.Join(warned, "\n"), "changed from "+Spid(pinned)+" to "+Spid(fresh)) {
			t.Fatalf("%s: смена ключа не в журнале: %q", name, warned)
		}
	}

	for name, refusal := range map[string]error{
		"garbled":   ErrMalformed,
		"too large": ErrTooLarge,
		"network":   errors.New("connection reset by peer"),
		"5xx":       errors.New("server answered with status 502"),
		"clock":     ErrStale,
		"foreign":   ErrMismatch,
	} {
		round, calls := rounds(fails(refusal, 0), answers(fresh))
		if _, _, err := Exchange(pinned, 0, round, warn); !errors.Is(err, refusal) || len(*calls) != 1 {
			t.Fatalf("%s: закрепление снято: %v %+v", name, err, *calls)
		}
	}

	// Без закрепления повторять без него нечего.
	round, calls := rounds(fails(ErrBadAnswer, 0))
	if _, _, err := Exchange(nil, 0, round, warn); !errors.Is(err, ErrBadAnswer) || len(*calls) != 1 {
		t.Fatalf("без закрепления: %v %+v", err, *calls)
	}
}

func TestExchangeCorrectsTheClockBeforeDoubtingTheKey(t *testing.T) {
	pinned := key(1)
	notFound := fmt.Errorf("%w: relay answered with status 404", ErrBadAnswer)
	served := time.Now().Unix() + 3600

	round, calls := rounds(fails(notFound, served), answers(pinned))
	answer, offset, err := Exchange(pinned, 0, round, func(string, ...any) {})
	if err != nil || answer == nil || len(*calls) != 2 || (*calls)[1].pin == nil {
		t.Fatalf("сбитые часы — повтор по часам прослойки с тем же ключом: %v %+v", err, *calls)
	}
	if offset < 3590 || offset > 3610 || (*calls)[1].offset != offset {
		t.Fatalf("поправка часов = %d", offset)
	}

	// Не хватило времени на повтор — остаётся исход первого обмена.
	round, calls = rounds(fails(notFound, served))
	if _, _, err := Exchange(pinned, 0, round, func(string, ...any) {}); !errors.Is(err, ErrBadAnswer) || len(*calls) != 3 {
		t.Fatalf("без времени на повтор: %v %+v", err, *calls)
	}
}
