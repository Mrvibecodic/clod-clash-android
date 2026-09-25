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
	"sync/atomic"
	"time"

	"cfa/native/app"
	"cfa/native/chanx"
	"cfa/native/config/delivery"
	"cfa/native/config/panel"

	"github.com/metacubex/mihomo/component/ca"
	"github.com/metacubex/mihomo/component/dialer"
	tlsC "github.com/metacubex/mihomo/component/tls"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/listener/inner"
	"github.com/metacubex/mihomo/log"
)

var secureChannel atomic.Bool

var errChanFingerprint = errors.New("clod-chan: chrome fingerprint is not available in the core")

var errChanAlpn = errors.New("clod-chan: relay negotiated a protocol other than http/1.1")

var errChanRedirects = errors.New("clod-chan: stopped after 10 redirects")

var errChanDowngrade = errors.New("clod-chan: refused redirect from https to plain http")

func refusedByChanRedirect(err error) bool {
	return errors.Is(err, errChanDowngrade) || errors.Is(err, errChanRedirects)
}

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
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 10 {
				return errChanRedirects
			}

			if via[0].URL.Scheme == "https" && req.URL.Scheme != "https" {
				return errChanDowngrade
			}

			return nil
		},
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
	_ = os.WriteFile(chanPinFile(dir), []byte(base64.RawURLEncoding.EncodeToString(pin)), 0600)
}

func chanSkewFile() string {
	return constant.Path.Resolve("chan.skew")
}

func readChanSkew() int64 {
	raw, err := os.ReadFile(chanSkewFile())
	if err != nil {
		return 0
	}

	offset, err := strconv.ParseInt(strings.TrimSpace(string(raw)), 10, 64)
	if err != nil || abs(offset) <= chanx.Skew {
		return 0
	}

	return offset
}

func storeChanSkew(offset int64) {
	if offset == 0 {
		_ = os.Remove(chanSkewFile())

		return
	}

	_ = os.WriteFile(chanSkewFile(), []byte(strconv.FormatInt(offset, 10)), 0600)
}

func openUrlSecure(rounds *roundBudget, url string, dir string, direct *directBudget) (io.ReadCloser, fetchHeader, error) {
	pin := readChanPin(dir)

	offset := readChanSkew()

	ctx, ok := rounds.start()
	if !ok {
		return nil, fetchHeader{}, errFetchBudget
	}

	answer, served, err := chanRound(ctx, url, pin, offset, direct)

	if err != nil {
		if next, changed := chanx.Correction(served, time.Now().Unix(), offset); changed {
			if next == 0 {
				log.Warnln("Secure channel: the stored clock correction of %d s is stale, retrying with the device clock", offset)
			} else {
				log.Warnln("Secure channel: device clock is %d s off the relay, retrying with the relay time", next)
			}

			offset = next

			if ctx, ok = rounds.start(); ok {
				answer, _, err = chanRound(ctx, url, pin, offset, direct)
			} else {
				log.Warnln("Secure channel: no time left for a round with the relay time")
			}
		}
	}

	if err != nil && pin != nil {
		log.Warnln("Secure channel: pinned relay key refused (%v), retrying without the pin", err)

		if ctx, ok = rounds.start(); ok {
			answer, _, err = chanRound(ctx, url, nil, offset, direct)
			if err == nil {
				_ = os.Remove(chanPinFile(dir))
			}
		} else {
			log.Warnln("Secure channel: no time left for a round without the pin")
		}
	}
	if err != nil {
		return nil, fetchHeader{}, err
	}

	writeChanPin(dir, answer.SP)

	storeChanSkew(offset)

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

	header := fetchHeader{
		SubscriptionUserInfo:  meta.Get("subscription-userinfo"),
		ProfileUpdateInterval: meta.Get("profile-update-interval"),
		Raw:                   map[string][]string(meta),
	}

	if delivery.NotAConfiguration([]byte(answer.Body)) && panel.RefusesDevice(meta) {
		return nil, header, errDeviceRefused
	}

	return io.NopCloser(strings.NewReader(answer.Body)), header, nil
}

func abs(v int64) int64 {
	if v < 0 {
		return -v
	}

	return v
}

func chanRound(ctx context.Context, url string, pin []byte, clockOffset int64, direct *directBudget) (*chanx.Answer, int64, error) {
	device := app.DeviceHeaders()

	fields := chanx.Fields{
		Hwid:   device["x-hwid"],
		OS:     device["x-device-os"],
		OSVer:  device["x-ver-os"],
		Model:  device["x-device-model"],
		UA:     "ClodClash/" + app.VersionName() + " (Android)",
		Accept: "*/*",
	}

	secureURL, session, err := chanx.Build(url, pin, fields, time.Now().Unix()+clockOffset)
	if err != nil {
		return nil, 0, err
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, secureURL, nil)
	if err != nil {
		return nil, 0, err
	}

	for _, pair := range chanBrowserHeaders {
		request.Header.Set(pair[0], pair[1])
	}

	response, err := chanClient(false).Do(request)
	if err != nil && !refusedByChanRedirect(err) {
		tunnelErr := err

		directCtx, ok := direct.start()
		if !ok {
			log.Warnln("Secure channel: request failed through the tunnel (%s), no time left for a direct retry", tunnelErr.Error())

			return nil, 0, tunnelErr
		}

		log.Warnln("Secure channel: request failed through the tunnel (%s), retrying directly", tunnelErr.Error())

		response, err = chanClient(true).Do(request.WithContext(directCtx))

		if err != nil {
			return nil, 0, tunnelErr
		}
	}

	if err != nil {
		return nil, 0, err
	}

	defer response.Body.Close()

	served := serverTime(response.Header)

	wire, err := io.ReadAll(io.LimitReader(response.Body, 32<<20))
	if err != nil {
		return nil, served, err
	}

	answer, err := session.Open(wire, time.Now().Unix()+clockOffset)

	return answer, served, err
}

func serverTime(header http.Header) int64 {
	if parsed, err := http.ParseTime(strings.TrimSpace(header.Get("Date"))); err == nil {
		return parsed.Unix()
	}

	return 0
}
