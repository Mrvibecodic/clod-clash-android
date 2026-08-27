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
	"strings"
	"sync/atomic"
	"time"

	"cfa/native/app"
	"cfa/native/chanx"

	"github.com/metacubex/mihomo/component/ca"
	"github.com/metacubex/mihomo/component/dialer"
	tlsC "github.com/metacubex/mihomo/component/tls"
	"github.com/metacubex/mihomo/listener/inner"
	"github.com/metacubex/mihomo/log"
)

var secureChannel atomic.Bool

var errChanFingerprint = errors.New("clod-chan: отпечаток chrome недоступен в ядре")

var errChanAlpn = errors.New("clod-chan: прослойка выбрала не http/1.1")

func SetSecureChannel(enabled bool) {
	secureChannel.Store(enabled)
}

func SecureChannel() bool {
	return secureChannel.Load()
}

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

func chanClient() *http.Client {
	transport := &http.Transport{
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: 10 * time.Second,
		DialTLSContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			conn, err := inner.HandleTcp(inner.GetTunnel(), address, "")
			if err != nil {
				conn, err = dialer.DialContext(ctx, network, address)
				if err != nil {
					return nil, err
				}
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

	return &http.Client{Transport: transport}
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
	_ = os.WriteFile(chanPinFile(dir), []byte(base64.RawURLEncoding.EncodeToString(pin)), 0600)
}

func openUrlSecure(ctx context.Context, url string, dir string) (io.ReadCloser, fetchHeader, error) {
	pin := readChanPin(dir)

	answer, err := chanRound(ctx, url, pin)
	if err != nil && pin != nil {
		log.Warnln("Secure channel: pinned relay key refused (%v), retrying without the pin", err)

		answer, err = chanRound(ctx, url, nil)
		if err == nil {
			_ = os.Remove(chanPinFile(dir))
		}
	}
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

	if answer.Status < 200 || answer.Status >= 300 {
		return nil, fetchHeader{}, fmt.Errorf("server answered with status %d", answer.Status)
	}

	log.Infoln("Subscription fetched over the secure channel")

	return io.NopCloser(strings.NewReader(answer.Body)), fetchHeader{
		SubscriptionUserInfo:  meta.Get("subscription-userinfo"),
		ProfileUpdateInterval: meta.Get("profile-update-interval"),
		Raw:                   map[string][]string(meta),
	}, nil
}

func chanRound(ctx context.Context, url string, pin []byte) (*chanx.Answer, error) {
	device := app.DeviceHeaders()

	fields := chanx.Fields{
		Hwid:   device["x-hwid"],
		OS:     device["x-device-os"],
		OSVer:  device["x-ver-os"],
		Model:  device["x-device-model"],
		UA:     "ClodClash/" + app.VersionName() + " (Android)",
		Accept: "*/*",
	}

	secureURL, session, err := chanx.Build(url, pin, fields, time.Now().Unix())
	if err != nil {
		return nil, err
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, secureURL, nil)
	if err != nil {
		return nil, err
	}

	for _, pair := range chanBrowserHeaders {
		request.Header.Set(pair[0], pair[1])
	}

	response, err := chanClient().Do(request)
	if err != nil {
		return nil, err
	}

	defer response.Body.Close()

	wire, err := io.ReadAll(io.LimitReader(response.Body, 32<<20))
	if err != nil {
		return nil, err
	}

	return session.Open(wire, time.Now().Unix())
}
