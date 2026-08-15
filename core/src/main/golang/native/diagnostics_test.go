package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/jpillora/chisel/share/settings"
	"github.com/metacubex/mihomo/log"
)

func TestDiagnosticsProbeAuthenticatesController(t *testing.T) {
	const secret = "controller-secret"
	server := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/controller/version" {
			t.Fatalf("path = %q", request.URL.Path)
		}
		if request.Header.Get("Authorization") != "Bearer "+secret {
			t.Fatal("controller request was not authenticated")
		}
		writer.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	if state := probeDiagnosticsController(server.Client(), server.URL, secret); state != diagnosticsRuntimeReady {
		t.Fatalf("authenticated controller state = %q", state)
	}
}

func TestDiagnosticsProbeRejectsUnauthorizedController(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()

	if state := probeDiagnosticsController(server.Client(), server.URL, "wrong-secret"); state != diagnosticsRuntimeAccessDenied {
		t.Fatalf("unauthorized controller state = %q", state)
	}
}

func TestDiagnosticsProbeClassifiesUnavailableController(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusBadGateway)
	}))
	defer server.Close()

	if state := probeDiagnosticsController(server.Client(), server.URL, "controller-secret"); state != diagnosticsRuntimeUnreachable {
		t.Fatalf("unavailable controller state = %q", state)
	}
}

func TestDiagnosticsHTTPClientUsesInjectedTunnelDialer(t *testing.T) {
	dialer := func(context.Context, string, string) (net.Conn, error) {
		return nil, errors.New("not called")
	}
	client := newDiagnosticsHTTPClient(dialer)
	transport, ok := client.Transport.(*http.Transport)
	if !ok || transport.DialContext == nil {
		t.Fatal("readiness probe does not use the diagnostics tunnel dialer")
	}
}

func TestDiagnosticsProbeTrackerPublishesTypedStates(t *testing.T) {
	tracker := diagnosticsProbeTracker{}
	for attempt := 1; attempt < diagnosticsProbeFailures; attempt++ {
		if state := tracker.Observe(diagnosticsRuntimeUnreachable); state != nil {
			t.Fatalf("attempt %d published premature state %q", attempt, *state)
		}
	}
	if state := tracker.Observe(diagnosticsRuntimeUnreachable); state == nil || *state != diagnosticsRuntimeUnreachable {
		t.Fatalf("fifth failure state = %v", state)
	}
	if state := tracker.Observe(diagnosticsRuntimeReady); state == nil || *state != diagnosticsRuntimeReady {
		t.Fatalf("ready state = %v", state)
	}
	if state := tracker.Observe(diagnosticsRuntimeAccessDenied); state == nil || *state != diagnosticsRuntimeAccessDenied {
		t.Fatalf("access denied state = %v", state)
	}
	if state := tracker.Observe(diagnosticsRuntimeConfigurationErr); state == nil || *state != diagnosticsRuntimeConfigurationErr {
		t.Fatalf("configuration state = %v", state)
	}
}

func TestDiagnosticsConfigurationUsesStandardTLSWithoutFingerprint(t *testing.T) {
	configuration, err := newDiagnosticsConfiguration("https://example.com", "user:password", 19091)
	if err != nil {
		t.Fatal(err)
	}
	if configuration.Fingerprint != "" {
		t.Fatalf("fingerprint = %q", configuration.Fingerprint)
	}
	if configuration.TLS.SkipVerify {
		t.Fatal("TLS verification must stay enabled")
	}
}

func TestDiagnosticsConfigurationUsesOfficialChiselAndUniqueContainerReverseRemote(t *testing.T) {
	configuration, err := newDiagnosticsConfiguration("https://example.com:8443/", "user:password", 19091)
	if err != nil {
		t.Fatal(err)
	}
	if configuration.Server != "https://example.com:8443" {
		t.Fatalf("server = %q", configuration.Server)
	}
	wantRemote := "R:0.0.0.0:19091:127.0.0.1:9090"
	if len(configuration.Remotes) != 1 || configuration.Remotes[0] != wantRemote {
		t.Fatalf("remotes = %#v, want only %q", configuration.Remotes, wantRemote)
	}
	if configuration.TLS.SkipVerify {
		t.Fatal("TLS verification must stay enabled")
	}
	if configuration.DialContext == nil {
		t.Fatal("diagnostic control connection must use the Mihomo inner tunnel")
	}
	if configuration.Fingerprint != "" || configuration.Auth != "user:password" {
		t.Fatal("standard TLS or supplied auth was not configured")
	}
	if configuration.MaxRetryCount != -1 {
		t.Fatal("diagnostics must keep retrying while the manual session is enabled")
	}

	client, err := newDiagnosticsClient(configuration)
	if err != nil {
		t.Fatal(err)
	}
	if client.Logger.Info || client.Logger.Debug {
		t.Fatal("official chisel logger must stay disabled")
	}
}

func TestProviderACLAllowsOnlyAssignedContainerReversePort(t *testing.T) {
	user := settings.User{
		Addrs: []*regexp.Regexp{
			regexp.MustCompile(`^R:0[.]0[.]0[.]0:19091$`),
		},
	}

	if !user.HasAccess("R:0.0.0.0:19091") {
		t.Fatal("assigned container reverse port must be allowed")
	}

	for _, remote := range []string{
		"R:0.0.0.0:19092",
		"R:127.0.0.1:19091",
		"127.0.0.1:9090",
	} {
		if user.HasAccess(remote) {
			t.Fatalf("unexpected provider ACL access to %q", remote)
		}
	}
}

func TestDiagnosticsConfigurationRejectsMissingAuth(t *testing.T) {
	if _, err := newDiagnosticsConfiguration("https://example.com", "", 19091); !errors.Is(err, errDiagnosticsAuthMissing) {
		t.Fatalf("error = %v", err)
	}
}

func TestDiagnosticsConfigurationRejectsUnsafeReversePorts(t *testing.T) {
	for _, port := range []int{-1, 0, 1023, 65536} {
		if _, err := newDiagnosticsConfiguration("https://example.com", "user:password", port); !errors.Is(err, errDiagnosticsRemotePortInvalid) {
			t.Errorf("port %d: error = %v", port, err)
		}
	}
}

func TestDiagnosticsConfigurationRejectsNonOriginEndpoints(t *testing.T) {
	for _, endpoint := range []string{
		"",
		"http://example.com",
		"https://user:password@example.com",
		"https://example.com/path",
		"https://example.com?query=value",
		"https://example.com#fragment",
		"https://example.com:0",
		"https://example.com:65536",
	} {
		if _, err := newDiagnosticsConfiguration(endpoint, "user:password", 19091); !errors.Is(err, errDiagnosticsEndpointInvalid) {
			t.Errorf("endpoint %q: error = %v", endpoint, err)
		} else if endpoint != "" && strings.Contains(err.Error(), endpoint) {
			t.Errorf("error exposed rejected endpoint %q", endpoint)
		}
	}
}

func TestDiagnosticsStatusReportsFailureWithoutInternalDetails(t *testing.T) {
	diagnosticsStart("http://example.com", "user:password", "controller-secret", 19091)
	status := diagnosticsQuery()
	if !strings.Contains(status, `"state":"CONFIGURATION_ERROR"`) {
		t.Fatalf("status did not report structured failure: %s", status)
	}
	if strings.Contains(status, `"failed"`) || strings.Contains(status, `"ready"`) || strings.Contains(status, "недоступна") || strings.Contains(status, "example.com") {
		t.Fatalf("status exposed internal error: %s", status)
	}

	diagnosticsStop()
	if status = diagnosticsQuery(); !strings.Contains(status, `"state":"CONNECTING"`) {
		t.Fatalf("stopped diagnostics retained stale failure: %s", status)
	}
}

func TestDiagnosticsLogAppearsInCoreLogStream(t *testing.T) {
	subscriber := log.Subscribe()
	defer log.UnSubscribe(subscriber)

	diagnosticsInfo("Запуск канала")

	awaitDiagnosticsLog(t, subscriber, "[APP] [Diagnostics] Запуск канала")
}

func awaitDiagnosticsLog(t *testing.T, subscriber <-chan log.Event, want string) string {
	t.Helper()
	timer := time.NewTimer(time.Second)
	defer timer.Stop()

	for {
		select {
		case message := <-subscriber:
			if strings.Contains(message.Payload, want) {
				return message.Payload
			}
		case <-timer.C:
			t.Fatalf("diagnostics log %q was not published", want)
		}
	}
}
