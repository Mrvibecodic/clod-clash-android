package report

import (
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/common/yaml"
)

// Узлы подписки: из её списка proxies и из провайдеров — встроенных (payload)
// и скачанных ядром в файл. В ядре узлы провайдера живут отдельно от списка
// proxies, и в отчёте без них не обойтись: у панели все узлы бывают именно там.

// ProviderSource — откуда у провайдера узлы: встроенные или файл ядра.
type ProviderSource struct {
	Inline []NodeInfo
	Path   string
}

type providerFile struct {
	modified time.Time
	nodes    []NodeInfo
}

var (
	providerMu    sync.Mutex
	providerFiles = map[string]providerFile{}
)

// NodesOf — узлы из списка proxies подписки: имя → тип, адрес и порт.
func NodesOf(raw []map[string]any) map[string]NodeInfo {
	out := map[string]NodeInfo{}

	for _, proxy := range raw {
		if info, ok := nodeOf(proxy); ok {
			out[info.Name] = info
		}
	}

	return out
}

func nodeOf(proxy map[string]any) (NodeInfo, bool) {
	name, _ := proxy["name"].(string)
	kind, _ := proxy["type"].(string)
	server, _ := proxy["server"].(string)
	port := portOf(proxy["port"])

	if name == "" || kind == "" || server == "" || port <= 0 || port > 65535 {
		return NodeInfo{}, false
	}

	return NodeInfo{Name: name, Type: strings.ToLower(kind), Server: server, Port: port}, true
}

func nodesOfAny(raw any) []NodeInfo {
	var out []NodeInfo

	switch list := raw.(type) {
	case []map[string]any:
		for _, proxy := range list {
			if info, ok := nodeOf(proxy); ok {
				out = append(out, info)
			}
		}
	case []any:
		for _, item := range list {
			proxy, ok := item.(map[string]any)
			if !ok {
				continue
			}
			if info, ok := nodeOf(proxy); ok {
				out = append(out, info)
			}
		}
	}

	return out
}

func portOf(value any) int {
	switch v := value.(type) {
	case int:
		return v
	case int64:
		return int(v)
	case uint64:
		return int(v)
	case float64:
		return int(v)
	case string:
		port, _ := strconv.Atoi(strings.TrimSpace(v))
		return port
	}

	return 0
}

// ProvidersOf — источники узлов провайдеров подписки после patchProviders:
// у скачиваемых path уже абсолютный.
func ProvidersOf(raw map[string]map[string]any) []ProviderSource {
	var out []ProviderSource

	for _, provider := range raw {
		kind, _ := provider["type"].(string)
		if strings.EqualFold(kind, "inline") {
			out = append(out, ProviderSource{Inline: nodesOfAny(provider["payload"])})

			continue
		}

		if path, _ := provider["path"].(string); path != "" {
			out = append(out, ProviderSource{Path: path})
		}
	}

	return out
}

// providerNodes — узлы провайдера; файл перечитывается, когда менялся, и
// молча пропускается, пока ядро его не скачало.
func providerNodes(source ProviderSource) []NodeInfo {
	if source.Path == "" {
		return source.Inline
	}

	stat, err := os.Stat(source.Path)
	if err != nil {
		return nil
	}

	providerMu.Lock()
	cached, ok := providerFiles[source.Path]
	providerMu.Unlock()

	if ok && cached.modified.Equal(stat.ModTime()) {
		return cached.nodes
	}

	var listed []NodeInfo

	if text, err := os.ReadFile(source.Path); err == nil {
		var doc struct {
			Proxies []map[string]any `yaml:"proxies"`
		}
		if yaml.Unmarshal(text, &doc) == nil {
			listed = nodesOfAny(doc.Proxies)
		}
	}

	providerMu.Lock()
	providerFiles[source.Path] = providerFile{modified: stat.ModTime(), nodes: listed}
	providerMu.Unlock()

	return listed
}

// AllNodes — узлы подписки и её провайдеров; одноимённым верх у подписки.
func AllNodes(own map[string]NodeInfo, providers []ProviderSource) map[string]NodeInfo {
	out := make(map[string]NodeInfo, len(own))
	for name, info := range own {
		out[name] = info
	}

	for _, source := range providers {
		for _, info := range providerNodes(source) {
			if _, taken := out[info.Name]; !taken {
				out[info.Name] = info
			}
		}
	}

	return out
}

// SetNodes — узлы подписки, загруженной в ядро, и её провайдеры.
func SetNodes(own map[string]NodeInfo, providers []ProviderSource) {
	mu.Lock()
	defer mu.Unlock()

	nodes = own
	nodeProviders = providers
}

// LoadedNodes — узлы подписки в ядре, с провайдерскими.
func LoadedNodes() map[string]NodeInfo {
	mu.Lock()
	own, providers := nodes, nodeProviders
	mu.Unlock()

	return AllNodes(own, providers)
}
