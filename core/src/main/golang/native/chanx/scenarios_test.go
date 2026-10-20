package chanx

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
)

// Сценарии подписки по каналу на настоящем HTTPS: провайдер без прослойки
// (панель с 404, nginx с 444, Cloudflare перед ним), прослойка с выключенным
// каналом (новая отвечает 404, старая — 502), прослойка с каналом, смена её
// ключа, часы сервера, WAF на выходе VPN, глухой прямой путь.

const scenarioToken = "abcdefghijklmnop"

// relay — прослойка: канал открывает своим ключом; запрос, который не
// открылся (чужой ключ, часы, канал выключен), идёт к панели как обычный путь.
type relay struct {
	mu      sync.Mutex
	on      bool
	secret  []byte
	public  []byte
	ahead   time.Duration
	unknown func(w http.ResponseWriter)
	opened  int
	// Ключ, с которым открыт последний запрос («0» — без ключа)
	lastSpid string
}

func newRelay(t *testing.T, on bool, unknown func(w http.ResponseWriter)) *relay {
	r := &relay{on: on, unknown: unknown}
	r.rotate(t)

	return r
}

func (r *relay) rotate(t *testing.T) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.secret = make([]byte, 32)
	if _, err := rand.Read(r.secret); err != nil {
		t.Fatal(err)
	}

	public, err := curve25519.X25519(r.secret, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}

	r.public = public
}

func (r *relay) key() []byte {
	r.mu.Lock()
	defer r.mu.Unlock()

	return append([]byte(nil), r.public...)
}

func (r *relay) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	now := time.Now().Add(r.ahead)
	w.Header().Set("Date", now.UTC().Format(http.TimeFormat))

	if body, ok := r.open(req.URL.Path, now.Unix()); ok {
		_, _ = w.Write(body)

		return
	}

	r.unknown(w)
}

func (r *relay) open(path string, now int64) ([]byte, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if !r.on {
		return nil, false
	}

	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
	if len(parts) != 4 || parts[0] != "c1" {
		return nil, false
	}

	kid, spid, blob := parts[1], parts[2], parts[3]
	psk := Psk(scenarioToken)
	if kid != Kid(psk, Epoch(now)) {
		return nil, false
	}

	raw, err := b64.DecodeString(blob)
	if err != nil || len(raw) < 32 {
		return nil, false
	}
	ephPub, cipher := raw[:32], raw[32:]

	var dh []byte
	switch spid {
	case "0":
	case Spid(r.public):
		if dh, err = curve25519.X25519(r.secret, ephPub); err != nil {
			return nil, false
		}
	default:
		return nil, false
	}

	aead, err := chacha20poly1305.New(hkdf32(concat(psk, dh), kid, "req"+string(ephPub)))
	if err != nil {
		return nil, false
	}
	plain, err := aead.Open(nil, make([]byte, chacha20poly1305.NonceSize), cipher, []byte("c1"+kid+string(ephPub)))
	if err != nil {
		return nil, false
	}

	var request struct {
		N string `json:"n"`
	}
	if json.Unmarshal(plain, &request) != nil {
		return nil, false
	}

	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return nil, false
	}
	public, _ := curve25519.X25519(secret, curve25519.Basepoint)
	shared, _ := curve25519.X25519(secret, ephPub)

	answer, _ := json.Marshal(map[string]any{
		"v": Version, "t": now, "n": request.N, "st": 200,
		"sp":   b64.EncodeToString(r.public),
		"meta": map[string][]string{},
		"body": "proxies: []\n",
	})

	sealer, err := chacha20poly1305.New(hkdf32(concat(psk, shared, dh), kid, "res"+string(ephPub)))
	if err != nil {
		return nil, false
	}
	sealed := sealer.Seal(nil, make([]byte, 12), answer, []byte("c1r"+kid+string(ephPub)+string(public)))

	r.opened++
	r.lastSpid = spid

	return []byte(b64.EncodeToString(concat(public, sealed))), true
}

func notFound(w http.ResponseWriter)   { w.WriteHeader(http.StatusNotFound) }
func badGateway(w http.ResponseWriter) { w.WriteHeader(http.StatusBadGateway) }
func originDown(w http.ResponseWriter) { w.WriteHeader(520) }
func forbidden(w http.ResponseWriter)  { w.WriteHeader(http.StatusForbidden) }

func hangUp(w http.ResponseWriter) {
	if conn, _, err := w.(http.Hijacker).Hijack(); err == nil {
		_ = conn.Close()
	}
}

func only(status func(http.ResponseWriter)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { status(w) })
}

// path — путь до провайдера: через VPN или напрямую. Глухой путь не отвечает
// до конца срока попытки, недоступный — отказывает сразу.
type path struct {
	server *httptest.Server
	dead   bool
	down   bool
}

// refusedCap — как budget.Detour в имитации: после отказа или обрыва через
// туннель прямой путь не дольше этого
const refusedCap = 300 * time.Millisecond

func (p path) way(end time.Time) Way {
	return func(refused bool) (*http.Client, context.Context, bool) {
		if time.Until(end) <= 0 {
			return nil, nil, false
		}

		deadline := end
		if refused && time.Until(deadline) > refusedCap {
			deadline = time.Now().Add(refusedCap)
		}

		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		time.AfterFunc(time.Until(deadline)+time.Second, cancel)

		dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
			switch {
			case p.dead:
				<-ctx.Done()

				return nil, ctx.Err()
			case p.down:
				return nil, errors.New("connect: connection refused")
			}

			return (&net.Dialer{}).DialContext(ctx, network, p.server.Listener.Addr().String())
		}

		return &http.Client{
			Transport: &http.Transport{
				DisableKeepAlives: true,
				DialContext:       dial,
				TLSClientConfig:   &tls.Config{InsecureSkipVerify: true},
			},
			CheckRedirect: CheckRedirect,
		}, ctx, true
	}
}

func serve(t *testing.T, handler http.Handler) *httptest.Server {
	server := httptest.NewTLSServer(handler)
	t.Cleanup(server.Close)

	return server
}

// fetch — как config.openUrlSecure: туннелю — срок без запаса на прямой путь,
// прямому — до конца окна.
func fetch(tunnel, direct path, pin []byte, window time.Duration) (*Answer, error) {
	end := time.Now().Add(window)
	answer, _, err := Fetch("https://sub.example/"+scenarioToken, pin, 0, Fields{}, http.Header{},
		tunnel.way(end.Add(-window/3)), direct.way(end), func(string, ...any) {})
	if err != nil {
		return nil, Silence(err)
	}

	return answer, nil
}

// Добавление подписки: как решает приложение — канал есть, канала нет
// (обычный путь) или повторить, а нет ответа — ошибка.
func addOutcome(err error) string {
	switch {
	case err == nil:
		return "channel"
	case errors.Is(err, ErrBadAnswer), errors.Is(err, ErrBadURL):
		return "absent"
	case errors.Is(err, ErrDropped), errors.Is(err, ErrDoubt), errors.Is(err, ErrFailed):
		return "doubtful"
	case errors.Is(err, ErrSilent):
		return "silent"
	}

	return "answered"
}

func TestAddingASubscriptionWithEveryProviderSetup(t *testing.T) {
	alive := func(handler http.Handler) path { return path{server: serve(t, handler)} }
	dead := path{dead: true}

	for _, row := range []struct {
		name   string
		tunnel path
		direct path
		want   string
	}{
		{"без прослойки, панель отвечает 404", alive(only(notFound)), dead, "absent"},
		{"без прослойки, nginx рвёт соединение (444)", alive(only(hangUp)), dead, "doubtful"},
		{"без прослойки, Cloudflare перед nginx с 444 (520)", alive(only(originDown)), dead, "doubtful"},
		{"прослойка, канал выключен, новая (404)", alive(newRelay(t, false, notFound)), dead, "absent"},
		{"прослойка, канал выключен, старая (502)", alive(newRelay(t, false, badGateway)), dead, "doubtful"},
		{"прослойка, канал включён", alive(newRelay(t, true, notFound)), dead, "channel"},
		{"прослойка, канал включён, без VPN до неё не достучаться", path{down: true}, alive(newRelay(t, true, notFound)), "channel"},
		{"прослойка, канал включён, WAF на выходе VPN", alive(only(forbidden)), alive(newRelay(t, true, notFound)), "channel"},
		{"провайдер не отвечает совсем", dead, dead, "silent"},
	} {
		t.Run(row.name, func(t *testing.T) {
			answer, err := fetch(row.tunnel, row.direct, nil, 3*time.Second)
			if got := addOutcome(err); got != row.want {
				t.Fatalf("исход %q, нужен %q (%v)", got, row.want, err)
			}
			if row.want == "channel" && len(answer.SP) != 32 {
				t.Fatal("ключ прослойки не пришёл")
			}
		})
	}
}

func TestUpdatingWhenTheRelayKeyChanges(t *testing.T) {
	dead := path{dead: true}

	for _, row := range []struct {
		name    string
		unknown func(http.ResponseWriter)
		direct  string
	}{
		{"новая прослойка, неузнанный запрос — 404", notFound, ""},
		{"старая прослойка, неузнанный запрос — 502", badGateway, ""},
		{"за прослойкой nginx рвёт неузнанный запрос", hangUp, ""},
		{"Cloudflare перед прослойкой (520)", originDown, ""},
		{"VPN выключен, путь один — прямой", notFound, "down"},
		{"VPN включён, но до провайдера через него глухо", notFound, "dead"},
		{"неузнанный запрос — 403, напрямую сервер тоже отвечает 403", forbidden, "forbidden"},
	} {
		t.Run(row.name, func(t *testing.T) {
			relay := newRelay(t, true, row.unknown)
			server := path{server: serve(t, relay)}

			stale := relay.key()
			relay.rotate(t)

			tunnel, direct := server, dead
			switch row.direct {
			case "down":
				tunnel, direct = path{down: true}, server
			case "dead":
				tunnel, direct = path{dead: true}, server
			case "forbidden":
				direct = path{server: serve(t, only(forbidden))}
			}

			answer, err := fetch(tunnel, direct, stale, 3*time.Second)
			if err != nil {
				t.Fatalf("новый ключ не взят: %v", err)
			}
			if string(answer.SP) != string(relay.key()) {
				t.Fatal("закреплён не новый ключ")
			}
		})
	}
}

func TestUpdatingWithAnUnchangedKey(t *testing.T) {
	relay := newRelay(t, true, notFound)
	server := path{server: serve(t, relay)}

	answer, err := fetch(server, path{dead: true}, relay.key(), 3*time.Second)
	if err != nil || string(answer.SP) != string(relay.key()) || relay.opened != 1 {
		t.Fatalf("обновление с прежним ключом: %v, обменов %d", err, relay.opened)
	}

	// WAF на выходе VPN, ключ прежний: напрямую с тем же ключом, а не без него
	answer, err = fetch(path{server: serve(t, only(forbidden))}, server, relay.key(), 3*time.Second)
	if err != nil || string(answer.SP) != string(relay.key()) || relay.lastSpid != Spid(relay.key()) {
		t.Fatalf("WAF на выходе VPN: %v, ключ запроса %q", err, relay.lastSpid)
	}
}

func TestServerClockAheadIsCorrected(t *testing.T) {
	relay := newRelay(t, true, notFound)
	relay.ahead = 2 * time.Hour
	server := path{server: serve(t, relay)}

	for _, pin := range [][]byte{nil, relay.key()} {
		if _, err := fetch(server, path{dead: true}, pin, 3*time.Second); err != nil {
			t.Fatalf("часы сервера впереди на 2 ч (ключ %v): %v", pin != nil, err)
		}
	}
}

func TestRelayDownKeepsThePinAndSaysSilent(t *testing.T) {
	relay := newRelay(t, true, notFound)

	_, err := fetch(path{dead: true}, path{dead: true}, relay.key(), 2*time.Second)
	if !errors.Is(err, ErrSilent) {
		t.Fatalf("недоступный провайдер не молчание: %v", err)
	}
}

func TestRedirectToPlainHTTPIsRefused(t *testing.T) {
	server := serve(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "http://sub.example/x", http.StatusFound)
	}))

	_, err := fetch(path{server: server}, path{dead: true}, nil, 3*time.Second)
	if !RefusedRedirect(err) || errors.Is(err, ErrSilent) {
		t.Fatalf("переадресация на http: %v", err)
	}
}

// Отказ или обрыв через туннель: сервер достижим, и глухой прямой путь не
// съедает окно попытки — каждая попытка добавления короткая.
func TestDeadDirectPathDoesNotStretchAnAttemptAfterARefusal(t *testing.T) {
	for name, status := range map[string]func(http.ResponseWriter){
		"nginx рвёт соединение (444)": hangUp,
		"WAF на выходе VPN (403)":     forbidden,
	} {
		started := time.Now()
		_, err := fetch(path{server: serve(t, only(status))}, path{dead: true}, nil, 3*time.Second)
		if took := time.Since(started); took > 1500*time.Millisecond {
			t.Fatalf("%s: попытка заняла %s (%v)", name, took, err)
		}
		if addOutcome(err) != "doubtful" {
			t.Fatalf("%s: исход %q", name, addOutcome(err))
		}
	}
}
