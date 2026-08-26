package config

import (
	"testing"

	mihomoConfig "github.com/metacubex/mihomo/config"
)

func useControllerSecret(t *testing.T, secret string) {
	t.Helper()
	previous := ExternalControllerSecret()
	if err := SetExternalControllerSecret(secret); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := SetExternalControllerSecret(previous); err != nil {
			t.Fatal(err)
		}
	})
}

func TestSetExternalControllerSecretUpdatesRunningController(t *testing.T) {
	previousApply := applyExternalControllerSecret
	t.Cleanup(func() { applyExternalControllerSecret = previousApply })

	var applied string
	applyExternalControllerSecret = func(secret string) { applied = secret }

	if err := SetExternalControllerSecret("live-controller-secret"); err != nil {
		t.Fatal(err)
	}
	if applied != "live-controller-secret" {
		t.Fatal("running controller did not receive the updated secret")
	}
}

func TestPatchExternalControllerUsesLoopbackAndAuthentication(t *testing.T) {
	useControllerSecret(t, "test-controller-secret")
	cfg := mihomoConfig.DefaultRawConfig()
	cfg.ExternalController = "0.0.0.0:1234"
	cfg.ExternalControllerTLS = "0.0.0.0:1235"

	if err := enforceExternalControllerAccess(cfg, ""); err != nil {
		t.Fatal(err)
	}
	if cfg.ExternalController != "127.0.0.1:9090" {
		t.Fatalf("external controller = %q", cfg.ExternalController)
	}
	if cfg.ExternalControllerTLS != "" {
		t.Fatalf("external TLS controller = %q", cfg.ExternalControllerTLS)
	}
	if cfg.AllowLan {
		t.Fatal("allow-lan must stay disabled")
	}
	if cfg.Secret != "test-controller-secret" {
		t.Fatalf("controller secret was not enforced")
	}
}

func TestExternalControllerOverrideCannotEscapeSecurityBoundary(t *testing.T) {
	useControllerSecret(t, "test-controller-secret")
	previousSessionOverride := sessionOverride
	sessionOverride = `{"allow-lan":true,"external-controller":"0.0.0.0:1234","external-controller-tls":"0.0.0.0:1235","secret":"attacker-controlled"}`
	t.Cleanup(func() { sessionOverride = previousSessionOverride })

	cfg := mihomoConfig.DefaultRawConfig()
	for _, apply := range processors[:2] {
		if err := apply(cfg, ""); err != nil {
			t.Fatal(err)
		}
	}

	if cfg.ExternalController != "127.0.0.1:9090" {
		t.Fatalf("external controller = %q", cfg.ExternalController)
	}
	if cfg.ExternalControllerTLS != "" {
		t.Fatalf("external TLS controller = %q", cfg.ExternalControllerTLS)
	}
	if cfg.AllowLan {
		t.Fatal("allow-lan override must not expose proxy listeners")
	}
	if cfg.Secret != "test-controller-secret" {
		t.Fatal("override replaced the controller secret")
	}
}

func TestExternalControllerSecretCannotBeBlank(t *testing.T) {
	if err := SetExternalControllerSecret(""); err == nil {
		t.Fatal("blank controller secret was accepted")
	}
}

func TestRotateExternalControllerSecretCreatesNewSecret(t *testing.T) {
	useControllerSecret(t, "previous-secret")

	if err := RotateExternalControllerSecret(); err != nil {
		t.Fatal(err)
	}
	if secret := ExternalControllerSecret(); secret == "" || secret == "previous-secret" {
		t.Fatalf("unexpected rotated controller secret %q", secret)
	}
}
