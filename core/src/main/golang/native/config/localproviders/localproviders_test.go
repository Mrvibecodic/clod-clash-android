package localproviders

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	adapter "github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	types "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/rules"
	RB "github.com/metacubex/mihomo/rules/bundle"
	provider "github.com/metacubex/mihomo/rules/provider"
	"github.com/metacubex/mihomo/tunnel"
)

// Ядро принимает пути провайдеров только внутри домашнего каталога
func home(t *testing.T) string {
	t.Helper()

	dir := t.TempDir()
	C.SetHomeDir(dir)

	return dir
}

func ruleProvider(t *testing.T, name string, mapping map[string]any) types.RuleProvider {
	t.Helper()

	// Как в разборе конфига ядром: провайдер сообщает туннелю об обновлении
	provider.SetTunnel(tunnel.Tunnel)

	p, err := provider.ParseRuleProvider(name, mapping, rules.ParseRule, RB.MakeBundleFile)
	if err != nil {
		t.Fatalf("parse %s: %v", name, err)
	}

	return p
}

func fileProvider(t *testing.T, name string, content string) types.RuleProvider {
	t.Helper()

	path := filepath.Join(home(t), name+".yaml")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}

	return ruleProvider(t, name, map[string]any{
		"type":     "file",
		"behavior": "classical",
		"format":   "yaml",
		"path":     path,
	})
}

func TestBrokenFileProviderRejected(t *testing.T) {
	cfg := &config.Config{
		RuleProviders: map[string]types.RuleProvider{
			"mine": fileProvider(t, "mine", "payload: [DOMAIN,example.com"),
		},
	}

	err := Initial(cfg)
	if err == nil {
		t.Fatal("broken provider file accepted")
	}

	if !strings.Contains(err.Error(), "rule provider mine") {
		t.Fatalf("error does not name the provider: %v", err)
	}
}

func TestMissingFileProviderRejected(t *testing.T) {
	cfg := &config.Config{
		RuleProviders: map[string]types.RuleProvider{
			"gone": ruleProvider(t, "gone", map[string]any{
				"type":     "file",
				"behavior": "classical",
				"format":   "yaml",
				"path":     filepath.Join(home(t), "gone.yaml"),
			}),
		},
	}

	err := Initial(cfg)
	if err == nil {
		t.Fatal("missing provider file accepted")
	}

	if !strings.Contains(err.Error(), "rule provider gone: file gone.yaml not found") || strings.Contains(err.Error(), string(filepath.Separator)) {
		t.Fatalf("error must name the file without its directory: %v", err)
	}
}

func proxyProvider(t *testing.T, name string, path string) types.ProxyProvider {
	t.Helper()

	p, err := adapter.ParseProxyProvider(name, map[string]any{
		"type":         "file",
		"path":         path,
		"health-check": map[string]any{"enable": false, "url": "https://example.com", "interval": 300},
	}, tunnel.Tunnel)
	if err != nil {
		t.Fatalf("parse %s: %v", name, err)
	}

	return p
}

func TestMissingFileProxyProviderRejected(t *testing.T) {
	cfg := &config.Config{
		Providers: map[string]types.ProxyProvider{
			"servers": proxyProvider(t, "servers", filepath.Join(home(t), "servers.yaml")),
		},
	}

	if err := Initial(cfg); err == nil || !strings.Contains(err.Error(), "proxy provider servers: file servers.yaml not found") {
		t.Fatalf("missing proxy provider file: %v", err)
	}
}

// Файл набора серверов не разбирается: разбор ядром трогает соединения
// работающего туннеля
func TestFileProxyProviderNotParsed(t *testing.T) {
	path := filepath.Join(home(t), "servers.yaml")
	if err := os.WriteFile(path, []byte("not a provider"), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg := &config.Config{
		Providers: map[string]types.ProxyProvider{
			"servers": proxyProvider(t, "servers", path),
		},
	}

	if err := Initial(cfg); err != nil {
		t.Fatalf("proxy provider file must only be checked for presence: %v", err)
	}
}

func TestValidFileProviderAccepted(t *testing.T) {
	cfg := &config.Config{
		RuleProviders: map[string]types.RuleProvider{
			"mine": fileProvider(t, "mine", "payload:\n  - DOMAIN,example.com\n"),
		},
	}

	if err := Initial(cfg); err != nil {
		t.Fatalf("valid provider file rejected: %v", err)
	}
}

func TestRemoteProviderFileNotRead(t *testing.T) {
	cfg := &config.Config{
		RuleProviders: map[string]types.RuleProvider{
			"remote": ruleProvider(t, "remote", map[string]any{
				"type":     "http",
				"behavior": "classical",
				"format":   "yaml",
				"url":      "https://example.com/rules.yaml",
				"path":     filepath.Join(home(t), "remote.yaml"),
				"interval": 86400,
			}),
		},
	}

	if err := Initial(cfg); err != nil {
		t.Fatalf("remote provider must be left to the core: %v", err)
	}
}
