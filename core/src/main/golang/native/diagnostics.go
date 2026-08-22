package main

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	chisel "github.com/jpillora/chisel/client"
	"github.com/metacubex/mihomo/log"
)

const (
	diagnosticsReverseHost   = "0.0.0.0"
	diagnosticsTarget        = "127.0.0.1:9090"
	diagnosticsMinPort       = 1024
	diagnosticsMaxPort       = 65535
	diagnosticsProbeFailures = 5
	diagnosticsProbeInterval = time.Second
)

var (
	errDiagnosticsFingerprintMissing = errors.New("diagnostics fingerprint is missing")
	errDiagnosticsAuthMissing        = errors.New("diagnostics authentication is missing")
	errDiagnosticsEndpointInvalid    = errors.New("diagnostics endpoint is invalid")
	errDiagnosticsRemotePortInvalid  = errors.New("diagnostics remote port is invalid")
)

// Empty in source builds. CI generates an untracked Go file whose init assigns it.
var diagnosticsFingerprint string

type diagnosticsStatus struct {
	Available bool `json:"available"`
	Running   bool `json:"running"`
	Ready     bool `json:"ready"`
	Failed    bool `json:"failed"`
}

var diagnostics = struct {
	sync.Mutex
	client  *chisel.Client
	running bool
	ready   bool
	failed  bool
}{}

var diagnosticsHTTPClient = &http.Client{
	Timeout: 3 * time.Second,
	CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
		return http.ErrUseLastResponse
	},
}

func diagnosticsInfo(message string) {
	log.Infoln("[APP] [Diagnostics] %s", message)
}

func diagnosticsWarning(message string) {
	log.Warnln("[APP] [Diagnostics] %s", message)
}

func diagnosticsSetFailed() {
	diagnostics.Lock()
	diagnostics.ready = false
	diagnostics.failed = true
	diagnostics.Unlock()
}

func probeDiagnosticsController(client *http.Client, endpoint, secret string) bool {
	request, err := http.NewRequest(http.MethodGet, strings.TrimRight(endpoint, "/")+"/controller/version", nil)
	if err != nil {
		return false
	}
	request.Header.Set("Authorization", "Bearer "+secret)
	response, err := client.Do(request)
	if err != nil {
		return false
	}
	_ = response.Body.Close()
	return response.StatusCode == http.StatusOK
}

func normalizeDiagnosticsEndpoint(raw string) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || !strings.EqualFold(parsed.Scheme, "https") || parsed.Hostname() == "" || parsed.User != nil ||
		(parsed.Path != "" && parsed.Path != "/") || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", errDiagnosticsEndpointInvalid
	}

	host := strings.ToLower(parsed.Hostname())
	if port := parsed.Port(); port != "" {
		portNumber, err := strconv.Atoi(port)
		if err != nil || portNumber < 1 || portNumber > 65535 {
			return "", errDiagnosticsEndpointInvalid
		}
		host = net.JoinHostPort(host, port)
	} else if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	return "https://" + host, nil
}

func newDiagnosticsConfiguration(endpoint, auth string, remotePort int) (chisel.Config, error) {
	if strings.TrimSpace(diagnosticsFingerprint) == "" {
		return chisel.Config{}, errDiagnosticsFingerprintMissing
	}
	if strings.TrimSpace(auth) == "" {
		return chisel.Config{}, errDiagnosticsAuthMissing
	}
	if remotePort < diagnosticsMinPort || remotePort > diagnosticsMaxPort {
		return chisel.Config{}, errDiagnosticsRemotePortInvalid
	}
	server, err := normalizeDiagnosticsEndpoint(endpoint)
	if err != nil {
		return chisel.Config{}, err
	}

	return chisel.Config{
		Server:      server,
		Fingerprint: diagnosticsFingerprint,
		Auth:        auth,
		Remotes: []string{
			"R:" + diagnosticsReverseHost + ":" + strconv.Itoa(remotePort) + ":" + diagnosticsTarget,
		},
		MaxRetryCount: -1,
		TLS: chisel.TLSConfig{
			SkipVerify: false,
		},
	}, nil
}

func newDiagnosticsClient(configuration chisel.Config) (*chisel.Client, error) {
	client, err := chisel.NewClient(&configuration)
	if err != nil {
		return nil, err
	}
	// Chisel's raw logger can include transport details. App diagnostics expose
	// only the bounded lifecycle messages below.
	client.Logger.Info = false
	client.Logger.Debug = false
	return client, nil
}

func diagnosticsStart(endpoint, auth, controllerSecret string, remotePort int) {
	if strings.TrimSpace(controllerSecret) == "" {
		diagnosticsSetFailed()
		diagnosticsWarning("Запуск отклонён: конфигурация недоступна")
		return
	}
	configuration, err := newDiagnosticsConfiguration(endpoint, auth, remotePort)
	if err != nil {
		diagnosticsSetFailed()
		diagnosticsWarning("Запуск отклонён: конфигурация недоступна")
		return
	}

	diagnostics.Lock()
	if diagnostics.client != nil {
		diagnostics.Unlock()
		return
	}

	client, err := newDiagnosticsClient(configuration)
	if err != nil {
		diagnostics.failed = true
		diagnostics.Unlock()
		diagnosticsWarning("Не удалось создать клиент")
		return
	}
	if err = client.Start(context.Background()); err != nil {
		_ = client.Close()
		diagnostics.failed = true
		diagnostics.Unlock()
		diagnosticsWarning("Не удалось запустить клиент")
		return
	}

	diagnostics.client = client
	diagnostics.running = true
	diagnostics.ready = false
	diagnostics.failed = false
	diagnostics.Unlock()
	diagnosticsInfo("Канал запущен")

	go diagnosticsWait(client)
	go diagnosticsProbe(client, configuration.Server, controllerSecret)
}

func diagnosticsProbe(client *chisel.Client, endpoint, controllerSecret string) {
	failures := 0
	for {
		ready := probeDiagnosticsController(diagnosticsHTTPClient, endpoint, controllerSecret)

		diagnostics.Lock()
		if diagnostics.client != client {
			diagnostics.Unlock()
			return
		}
		if ready {
			failures = 0
			diagnostics.ready = true
			diagnostics.failed = false
		} else {
			failures++
			diagnostics.ready = false
			diagnostics.failed = failures >= diagnosticsProbeFailures
		}
		diagnostics.Unlock()

		time.Sleep(diagnosticsProbeInterval)
	}
}

func diagnosticsWait(client *chisel.Client) {
	waitErr := client.Wait()

	diagnostics.Lock()
	if diagnostics.client != client {
		diagnostics.Unlock()
		return
	}
	diagnostics.client = nil
	diagnostics.running = false
	diagnostics.ready = false
	diagnostics.failed = true
	diagnostics.Unlock()

	if waitErr != nil {
		diagnosticsWarning("Канал остановлен из-за внутренней ошибки")
	} else {
		diagnosticsWarning("Канал остановлен")
	}
}

func diagnosticsStop() {
	diagnostics.Lock()
	client := diagnostics.client
	diagnostics.client = nil
	diagnostics.running = false
	diagnostics.ready = false
	diagnostics.failed = false
	diagnostics.Unlock()

	if client != nil {
		_ = client.Close()
		diagnosticsInfo("Канал выключен")
	}
}

func diagnosticsQuery() string {
	diagnostics.Lock()
	status := diagnosticsStatus{
		Available: strings.TrimSpace(diagnosticsFingerprint) != "",
		Running:   diagnostics.running,
		Ready:     diagnostics.ready,
		Failed:    diagnostics.failed,
	}
	diagnostics.Unlock()

	payload, _ := json.Marshal(status)
	return string(payload)
}
