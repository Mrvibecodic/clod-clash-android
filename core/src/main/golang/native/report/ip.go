package report

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/component/ca"
	"github.com/metacubex/mihomo/component/dialer"
)

// Внешний адрес клиента в текущей сети — тот, с которого идут замеры.
// Спрашивается у Яндекса (адреса страницы yandex.ru/internet) мимо туннеля:
// сокет ядра защищён от VPN, как у загрузки подписки напрямую.

const (
	ipv4URL   = "https://ipv4-internet.yandex.net/api/v0/ip"
	ipv6URL   = "https://ipv6-internet.yandex.net/api/v0/ip"
	ipTimeout = 10 * time.Second
	browserUA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
)

// ParseAddress — ответ адресом строкой, обычно в кавычках JSON; адрес не того
// семейства — пусто.
func ParseAddress(body string, v6 bool) string {
	text := strings.TrimSpace(strings.Trim(strings.TrimSpace(body), `"`))

	ip := net.ParseIP(text)
	if ip == nil || (ip.To4() == nil) != v6 {
		return ""
	}

	return ip.String()
}

func directClient() *http.Client {
	transport := &http.Transport{
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: ipTimeout,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return dialer.DialContext(ctx, network, address)
		},
	}

	transport.TLSClientConfig = &tls.Config{RootCAs: ca.GetCertPool()}

	return &http.Client{Transport: transport, Timeout: ipTimeout}
}

func ask(url string, v6 bool) string {
	ctx, cancel := context.WithTimeout(context.Background(), ipTimeout)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return ""
	}
	request.Header.Set("User-Agent", browserUA)

	response, err := directClient().Do(request)
	if err != nil {
		return ""
	}
	defer response.Body.Close()

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return ""
	}

	body, err := io.ReadAll(io.LimitReader(response.Body, 256))
	if err != nil {
		return ""
	}

	return ParseAddress(string(body), v6)
}

// Address — IPv4 и IPv6 клиента; пусто — не узнали (нет IPv6, сайт не ответил).
var Address = func() (string, string) {
	var ip4, ip6 string
	var wg sync.WaitGroup

	wg.Add(2)
	go func() { defer wg.Done(); ip4 = ask(ipv4URL, false) }()
	go func() { defer wg.Done(); ip6 = ask(ipv6URL, true) }()
	wg.Wait()

	return ip4, ip6
}
