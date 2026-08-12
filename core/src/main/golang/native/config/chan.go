package config

import (
	"context"
	"encoding/base64"
	"io"
	"net/http"
	"os"
	P "path"
	"strings"
	"sync/atomic"
	"time"

	"cfa/native/app"
	"cfa/native/chanx"

	clashHttp "github.com/metacubex/mihomo/component/http"
	"github.com/metacubex/mihomo/log"
)

// Защищённый канал до прослойки (протокол c1).
//
// Признак ставится из Kotlin перед загрузкой — тем же способом, каким ядру
// отдаётся секретный ключ age: менять подпись `fetchAndValid` через Go export →
// C → JNI ради одного флага дороже, чем поставить его отдельным вызовом.
//
// Ключ прослойки, закреплённый при первом успехе, живёт файлом рядом
// с конфигурацией. В Room его класть незачем: он относится к подписке,
// переезжает вместе с её каталогом и не нужен ни одному экрану.
var secureChannel atomic.Bool

// SetSecureChannel — включить защищённый канал для следующей загрузки.
func SetSecureChannel(enabled bool) {
	secureChannel.Store(enabled)
}

// SecureChannel — текущий признак; нужен разборщику ответа.
func SecureChannel() bool {
	return secureChannel.Load()
}

// User-Agent защищённого запроса.
//
// Наружу уходит самый скучный из возможных: настоящий UA едет внутрь шифра,
// а посреднику незачем знать, что за приложение к нему пришло. Пустой UA хуже —
// часть WAF режет запросы без него.
const chanNeutralUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

func chanPinFile(dir string) string {
	return P.Join(dir, "chan.pin")
}

func readChanPin(dir string) []byte {
	raw, err := os.ReadFile(chanPinFile(dir))
	if err != nil {
		return nil
	}

	pin, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(pin) != 32 {
		return nil
	}

	return pin
}

func writeChanPin(dir string, pin []byte) {
	if len(pin) != 32 {
		return
	}

	_ = os.MkdirAll(dir, 0700)
	_ = os.WriteFile(chanPinFile(dir), []byte(base64.RawURLEncoding.EncodeToString(pin)), 0600)
}

// openUrlSecure — загрузка подписки по защищённому каналу.
//
// Наружу не уходит ничего, кроме пути `/c1/…`: ни адреса подписки, ни `x-hwid`,
// ни карточки устройства. Ответ приходит сплошным шифротекстом, из которого
// восстанавливаются и тело, и все заголовки панели — дальше по коду они
// разбираются ровно теми же функциями, что и в открытом режиме.
func openUrlSecure(ctx context.Context, url string, dir string) (io.ReadCloser, fetchHeader, error) {
	device := app.DeviceHeaders()

	fields := chanx.Fields{
		Hwid:   device["x-hwid"],
		OS:     device["x-device-os"],
		OSVer:  device["x-ver-os"],
		Model:  device["x-device-model"],
		UA:     "ClodClash/" + app.VersionName() + " (Android)",
		Accept: "*/*",
	}

	now := time.Now().Unix()

	secureURL, session, err := chanx.Build(url, readChanPin(dir), fields, now)
	if err != nil {
		return nil, fetchHeader{}, err
	}

	header := http.Header{"User-Agent": {chanNeutralUA}, "Accept": {"*/*"}}

	response, err := clashHttp.HttpRequest(ctx, secureURL, http.MethodGet, header, nil)
	if err != nil {
		return nil, fetchHeader{}, err
	}

	defer response.Body.Close()

	wire, err := io.ReadAll(io.LimitReader(response.Body, 32<<20))
	if err != nil {
		return nil, fetchHeader{}, err
	}

	answer, err := session.Open(wire, time.Now().Unix())
	if err != nil {
		return nil, fetchHeader{}, err
	}

	writeChanPin(dir, answer.SP)

	meta := http.Header{}
	for name, values := range answer.Meta {
		for _, value := range values {
			meta.Add(name, value)
		}
	}

	log.Infoln("Subscription fetched over the secure channel")

	return io.NopCloser(strings.NewReader(answer.Body)), fetchHeader{
		SubscriptionUserInfo:  meta.Get("subscription-userinfo"),
		ProfileUpdateInterval: meta.Get("profile-update-interval"),
		Raw:                   map[string][]string(meta),
	}, nil
}
