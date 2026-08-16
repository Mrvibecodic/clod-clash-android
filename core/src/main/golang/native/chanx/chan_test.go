package chanx

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"

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
	Response struct {
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
	if _, _, _, err := split("https://sub.dom/"); err == nil {
		t.Fatal("адрес без токена обязан отбиваться")
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
