package config

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	P "path"
	"strconv"
	"strings"
	"time"

	"cfa/native/app"
	"cfa/native/chanx"
	"cfa/native/common"
	"cfa/native/config/delivery"
	"cfa/native/config/panel"

	"github.com/metacubex/mihomo/component/ca"
	"github.com/metacubex/mihomo/component/dialer"
	tlsC "github.com/metacubex/mihomo/component/tls"
	"github.com/metacubex/mihomo/listener/inner"
	"github.com/metacubex/mihomo/log"
)

var errChanFingerprint = errors.New("clod-chan: chrome fingerprint is not available in the core")

var errChanAlpn = errors.New("clod-chan: relay negotiated a protocol other than http/1.1")

var chanBrowserHeaders = [][2]string{
	{"sec-ch-ua", `"Chromium";v="140", "Not=A?Brand";v="24", "Google Chrome";v="140"`},
	{"sec-ch-ua-mobile", "?0"},
	{"sec-ch-ua-platform", `"Windows"`},
	{"upgrade-insecure-requests", "1"},
	{"user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"},
	{"accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"},
	{"sec-fetch-site", "none"},
	{"sec-fetch-mode", "navigate"},
	{"sec-fetch-user", "?1"},
	{"sec-fetch-dest", "document"},
	{"accept-language", "en-US,en;q=0.9"},
}

// chanHeaders — внешние заголовки запросов канала: одинаковый у всех набор
// браузера, ничего своего.
func chanHeaders() http.Header {
	header := http.Header{}
	for _, pair := range chanBrowserHeaders {
		header.Set(pair[0], pair[1])
	}

	return header
}

func chanClient(direct bool) *http.Client {
	transport := &http.Transport{
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: 10 * time.Second,
		DialTLSContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			var conn net.Conn
			var err error

			if direct {
				conn, err = dialer.DialContext(ctx, network, address)
			} else {
				conn, err = inner.HandleTcp(inner.GetTunnel(), address, "")
				if err != nil {
					conn, err = dialer.DialContext(ctx, network, address)
				}
			}

			if err != nil {
				return nil, err
			}

			host, _, err := net.SplitHostPort(address)
			if err != nil {
				host = address
			}

			config, err := ca.GetTLSConfig(ca.Option{})
			if err != nil {
				_ = conn.Close()

				return nil, err
			}

			config.ServerName = host
			config.NextProtos = []string{"http/1.1"}

			fingerprint, ok := tlsC.GetFingerprint("chrome")
			if !ok {
				_ = conn.Close()

				return nil, errChanFingerprint
			}

			tlsConn := tlsC.UClient(conn, tlsC.UConfig(config), fingerprint)
			if err := tlsC.BuildWebsocketHandshakeState(tlsConn); err != nil {
				_ = conn.Close()

				return nil, err
			}

			if err := tlsConn.HandshakeContext(ctx); err != nil {
				_ = conn.Close()

				return nil, err
			}

			if proto := tlsConn.ConnectionState().NegotiatedProtocol; proto != "" && proto != "http/1.1" {
				_ = tlsConn.Close()

				return nil, fmt.Errorf("%w: %s", errChanAlpn, proto)
			}

			return tlsConn, nil
		},
	}

	return &http.Client{
		Transport:     transport,
		CheckRedirect: chanx.CheckRedirect,
	}
}

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
	_ = common.WriteFile(chanPinFile(dir), []byte(base64.RawURLEncoding.EncodeToString(pin)), true)
}

func chanSkewFile(dir string) string {
	return P.Join(dir, "chan.skew")
}

func readChanSkew(dir string) int64 {
	raw, err := os.ReadFile(chanSkewFile(dir))
	if err != nil {
		return 0
	}

	offset, err := strconv.ParseInt(strings.TrimSpace(string(raw)), 10, 64)
	if err != nil || chanx.Abs(offset) <= chanx.Skew {
		return 0
	}

	return offset
}

func storeChanSkew(dir string, offset int64) {
	if offset == 0 {
		_ = os.Remove(chanSkewFile(dir))

		return
	}

	_ = common.WriteFile(chanSkewFile(dir), []byte(strconv.FormatInt(offset, 10)), false)
}

func openUrlSecure(rounds *roundBudget, url string, dir string, direct *directBudget) (io.ReadCloser, fetchHeader, error) {
	tunnel := func(bool) (*http.Client, context.Context, bool) {
		ctx, ok := rounds.start()

		return chanClient(false), ctx, ok
	}
	directly := func(refused bool) (*http.Client, context.Context, bool) {
		ctx, ok := direct.start(refused)

		return chanClient(true), ctx, ok
	}

	answer, offset, err := chanx.Fetch(url, readChanPin(dir), readChanSkew(dir), chanFields(), chanHeaders(), tunnel, directly, log.Warnln)
	if errors.Is(err, chanx.ErrNoRound) {
		err = errFetchBudget
	}
	if err != nil {
		return nil, fetchHeader{}, chanx.Silence(err)
	}

	writeChanPin(dir, answer.SP)

	storeChanSkew(dir, offset)

	meta := http.Header{}
	for name, values := range answer.Meta {
		for _, value := range values {
			meta.Add(name, value)
		}
	}

	// Отпечаток ключа прослойки — для свойств подписки: по нему сверяют, что
	// отвечала та самая прослойка.
	meta.Set("clod-chan-key", chanx.Spid(answer.SP))

	if answer.Status < 200 || answer.Status >= 300 {
		return nil, fetchHeader{}, fmt.Errorf("server answered with status %d", answer.Status)
	}

	log.Infoln("Subscription fetched over the secure channel")

	header := fetchHeaderOf(meta)

	if delivery.NotAConfiguration([]byte(answer.Body)) && panel.RefusesDevice(meta) {
		return nil, header, errDeviceRefused
	}

	return io.NopCloser(strings.NewReader(answer.Body)), header, nil
}

func chanFields() chanx.Fields {
	return fieldsOf(app.DeviceHeaders())
}

// fieldsOf — поля конверта по заголовкам опознания (nil — без опознания)
func fieldsOf(device map[string]string) chanx.Fields {
	return chanx.Fields{
		Hwid:   device["x-hwid"],
		OS:     device["x-device-os"],
		OSVer:  device["x-ver-os"],
		Model:  device["x-device-model"],
		UA:     userAgent(),
		Accept: "*/*",
	}
}
