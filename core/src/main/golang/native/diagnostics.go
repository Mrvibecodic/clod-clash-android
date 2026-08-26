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
	"github.com/metacubex/mihomo/listener/inner"
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
	errDiagnosticsAuthMissing       = errors.New("diagnostics authentication is missing")
	errDiagnosticsEndpointInvalid   = errors.New("diagnostics endpoint is invalid")
	errDiagnosticsRemotePortInvalid = errors.New("diagnostics remote port is invalid")
)

type diagnosticsRuntimeState string

const (
	diagnosticsRuntimeConnecting       diagnosticsRuntimeState = "CONNECTING"
	diagnosticsRuntimeReady            diagnosticsRuntimeState = "READY"
	diagnosticsRuntimeConfigurationErr diagnosticsRuntimeState = "CONFIGURATION_ERROR"
	diagnosticsRuntimeAccessDenied     diagnosticsRuntimeState = "ACCESS_DENIED"
	diagnosticsRuntimeUnreachable      diagnosticsRuntimeState = "UNREACHABLE"
)

type diagnosticsStatus struct {
	State diagnosticsRuntimeState `json:"state"`
}

type diagnosticsProbeTracker struct {
	lastState    diagnosticsRuntimeState
	failures     int
	published    diagnosticsRuntimeState
	hasPublished bool
}

func (tracker *diagnosticsProbeTracker) Observe(state diagnosticsRuntimeState) *diagnosticsRuntimeState {
	if state == diagnosticsRuntimeReady {
		tracker.lastState = state
		tracker.failures = 0
		if tracker.hasPublished && tracker.published == state {
			return nil
		}
		tracker.published = state
		tracker.hasPublished = true
		return &state
	}
	if state != tracker.lastState {
		tracker.lastState = state
		tracker.failures = 0
	}
	tracker.failures++
	if state == diagnosticsRuntimeAccessDenied || state == diagnosticsRuntimeConfigurationErr || tracker.failures >= diagnosticsProbeFailures {
		if tracker.hasPublished && tracker.published == state {
			return nil
		}
		tracker.published = state
		tracker.hasPublished = true
		return &state
	}
	return nil
}

var diagnostics = struct {
	sync.Mutex
	client *chisel.Client
	state  diagnosticsRuntimeState
}{state: diagnosticsRuntimeConnecting}

func dialDiagnosticsTunnel(ctx context.Context, network, address string) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if network != "tcp" && network != "tcp4" && network != "tcp6" {
		return nil, net.UnknownNetworkError(network)
	}
	return inner.HandleTcp(inner.GetTunnel(), address, "")
}

func newDiagnosticsHTTPClient(dialContext func(context.Context, string, string) (net.Conn, error)) *http.Client {
	return &http.Client{
		Transport: &http.Transport{
			DialContext:         dialContext,
			TLSHandshakeTimeout: 3 * time.Second,
		},
		Timeout: 3 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

var diagnosticsHTTPClient = newDiagnosticsHTTPClient(dialDiagnosticsTunnel)

func diagnosticsSetState(state diagnosticsRuntimeState) {
	diagnostics.Lock()
	diagnostics.state = state
	diagnostics.Unlock()
}

func probeDiagnosticsController(client *http.Client, endpoint, secret string) diagnosticsRuntimeState {
	request, err := http.NewRequest(http.MethodGet, strings.TrimRight(endpoint, "/")+"/controller/version", nil)
	if err != nil {
		return diagnosticsRuntimeConfigurationErr
	}
	request.Header.Set("Authorization", "Bearer "+secret)
	response, err := client.Do(request)
	if err != nil {
		return diagnosticsRuntimeUnreachable
	}
	_ = response.Body.Close()
	if response.StatusCode == http.StatusOK {
		return diagnosticsRuntimeReady
	}
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		return diagnosticsRuntimeAccessDenied
	}
	return diagnosticsRuntimeUnreachable
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
		Server: server,
		Auth:   auth,
		Remotes: []string{
			"R:" + diagnosticsReverseHost + ":" + strconv.Itoa(remotePort) + ":" + diagnosticsTarget,
		},
		MaxRetryCount: -1,
		DialContext:   dialDiagnosticsTunnel,
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
	diagnosticsRecordEvent(diagnosticsEventNativeStartRequested)
	if strings.TrimSpace(controllerSecret) == "" {
		diagnosticsSetState(diagnosticsRuntimeConfigurationErr)
		diagnosticsRecordEvent(diagnosticsEventNativeStartRejectedControllerSecret)
		return
	}
	configuration, err := newDiagnosticsConfiguration(endpoint, auth, remotePort)
	if err != nil {
		diagnosticsSetState(diagnosticsRuntimeConfigurationErr)
		switch {
		case errors.Is(err, errDiagnosticsAuthMissing):
			diagnosticsRecordEvent(diagnosticsEventNativeStartRejectedAuth)
		case errors.Is(err, errDiagnosticsRemotePortInvalid):
			diagnosticsRecordEvent(diagnosticsEventNativeStartRejectedRemotePort)
		default:
			diagnosticsRecordEvent(diagnosticsEventNativeStartRejectedEndpoint)
		}
		return
	}

	diagnostics.Lock()
	if diagnostics.client != nil {
		diagnostics.Unlock()
		diagnosticsRecordEvent(diagnosticsEventNativeStartIgnoredAlreadyRunning)
		return
	}

	client, err := newDiagnosticsClient(configuration)
	if err != nil {
		diagnostics.state = diagnosticsRuntimeConfigurationErr
		diagnostics.Unlock()
		diagnosticsRecordEvent(diagnosticsEventNativeClientCreateFailed)
		return
	}
	diagnosticsRecordEvent(diagnosticsEventNativeClientCreated)
	if err = client.Start(context.Background()); err != nil {
		_ = client.Close()
		diagnostics.state = diagnosticsRuntimeUnreachable
		diagnostics.Unlock()
		diagnosticsRecordEvent(diagnosticsEventNativeTransportStartFailed)
		return
	}

	diagnostics.client = client
	diagnostics.state = diagnosticsRuntimeConnecting
	diagnostics.Unlock()
	diagnosticsRecordEvent(diagnosticsEventNativeTransportWorkerStarted)
	diagnosticsRecordEvent(diagnosticsEventNativeProbeStarted)

	go diagnosticsWait(client)
	go diagnosticsProbe(client, configuration.Server, controllerSecret)
}

func diagnosticsProbe(client *chisel.Client, endpoint, controllerSecret string) {
	tracker := diagnosticsProbeTracker{}
	for {
		observed := probeDiagnosticsController(diagnosticsHTTPClient, endpoint, controllerSecret)
		published := tracker.Observe(observed)

		diagnostics.Lock()
		if diagnostics.client != client {
			diagnostics.Unlock()
			return
		}
		if published != nil {
			diagnostics.state = *published
		}
		diagnostics.Unlock()
		if published != nil {
			switch *published {
			case diagnosticsRuntimeReady:
				diagnosticsRecordEvent(diagnosticsEventNativeProbeReady)
			case diagnosticsRuntimeAccessDenied:
				diagnosticsRecordEvent(diagnosticsEventNativeProbeAccessDenied)
			case diagnosticsRuntimeUnreachable, diagnosticsRuntimeConfigurationErr:
				diagnosticsRecordEvent(diagnosticsEventNativeProbeUnreachable)
			}
		}

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
	diagnostics.state = diagnosticsRuntimeUnreachable
	diagnostics.Unlock()

	if waitErr != nil {
		diagnosticsRecordEvent(diagnosticsEventNativeTransportStoppedUnexpected)
	} else {
		diagnosticsRecordEvent(diagnosticsEventNativeTransportStoppedExpected)
	}
}

func diagnosticsStop() {
	diagnosticsRecordEvent(diagnosticsEventNativeStopRequested)
	diagnostics.Lock()
	client := diagnostics.client
	diagnostics.client = nil
	diagnostics.state = diagnosticsRuntimeConnecting
	diagnostics.Unlock()

	if client != nil {
		_ = client.Close()
		diagnosticsRecordEvent(diagnosticsEventNativeStopCompleted)
	} else {
		diagnosticsRecordEvent(diagnosticsEventNativeStopNoop)
	}
}

func diagnosticsQuery() string {
	diagnostics.Lock()
	status := diagnosticsStatus{
		State: diagnostics.state,
	}
	diagnostics.Unlock()

	payload, _ := json.Marshal(status)
	return string(payload)
}
