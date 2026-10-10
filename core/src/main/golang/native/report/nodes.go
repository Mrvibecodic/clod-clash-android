package report

import (
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/common/yaml"
)

// Узлы подписки: из её списка proxies и из провайдеров — встроенных (payload)
// и скачанных ядром в файл. В ядре узлы провайдера живут отдельно от списка
// proxies, и в отчёте без них не обойтись: у панели все узлы бывают именно там.
// Имена — такие, какими их заводит ядро: с приставками провайдера, без
// исключённых типов. Правила те же, что у ПК (config/proxy_label.rs).

// ProviderSource — откуда у провайдера узлы и как ядро их переделывает.
type ProviderSource struct {
	Name   string
	Inline []NodeInfo
	Path   string
	shape  shaping
	// Запасной набор провайдера из файла (payload), пока файл не прочитан ядром.
	fallback []NodeInfo
}

// shaping — что провайдер делает с узлами по дороге в ядро: исключённые типы и
// приставки к имени (override).
type shaping struct {
	excludeTypes []string
	prefix       string
	suffix       string
}

type providerFile struct {
	modified time.Time
	proxies  []map[string]any
}

var (
	providerMu    sync.Mutex
	providerFiles = map[string]providerFile{}
)

// fieldOf — поле записи, как его находит декодер ядра: точный ключ, иначе без
// учёта регистра (у узлов ещё и с «_» вместо «-»).
func fieldOf(m map[string]any, key string, underscores bool) (any, bool) {
	if value, ok := m[key]; ok {
		return value, true
	}

	for k, value := range m {
		if underscores {
			k = strings.ReplaceAll(k, "_", "-")
		}
		if strings.EqualFold(k, key) {
			return value, true
		}
	}

	return nil, false
}

func mapOf(value any) map[string]any {
	switch m := value.(type) {
	case map[string]any:
		return m
	case map[any]any:
		out := make(map[string]any, len(m))
		for k, v := range m {
			if key, ok := k.(string); ok {
				out[key] = v
			}
		}

		return out
	}

	return nil
}

// firstText — строка поля или первая строка списка.
func firstText(value any) string {
	switch v := value.(type) {
	case string:
		return strings.TrimSpace(v)
	case []any:
		if len(v) > 0 {
			if text, ok := v[0].(string); ok {
				return strings.TrimSpace(text)
			}
		}
	case []string:
		if len(v) > 0 {
			return strings.TrimSpace(v[0])
		}
	}

	return ""
}

func opt(m map[string]any, key string) any {
	value, _ := fieldOf(m, key, true)

	return value
}

// inner — поле key блока опций block узла.
func inner(proxy map[string]any, block, key string) string {
	return firstText(opt(mapOf(opt(proxy, block)), key))
}

// hostHeader — заголовок Host блока опций block узла.
func hostHeader(proxy map[string]any, block string) string {
	return firstText(opt(mapOf(opt(mapOf(opt(proxy, block)), "headers")), "host"))
}

// Via — чем узел отличается от соседей на том же адресе и порту — так панель
// разводит хосты за одним доменом: транспорт, имя сервера TLS, Host и путь (у
// gRPC — имя сервиса). Пусто, если ничего из этого нет. Правило то же, что у
// ПК (via в config/proxy_label.rs): по этой строке прослойка узнаёт один узел с
// обеих платформ.
func Via(kind string, proxy map[string]any) string {
	network := asciiLower(firstText(opt(proxy, "network")))

	sni := firstText(opt(proxy, "servername"))
	if sni == "" {
		sni = firstText(opt(proxy, "sni"))
	}
	sni = asciiLower(sni)

	var host, path string
	switch {
	case network == "ws":
		host, path = hostHeader(proxy, "ws-opts"), inner(proxy, "ws-opts", "path")
	case network == "grpc":
		path = inner(proxy, "grpc-opts", "grpc-service-name")
	case network == "h2":
		host, path = inner(proxy, "h2-opts", "host"), inner(proxy, "h2-opts", "path")
	case network == "http":
		host, path = hostHeader(proxy, "http-opts"), inner(proxy, "http-opts", "path")
	case network == "xhttp":
		host, path = inner(proxy, "xhttp-opts", "host"), inner(proxy, "xhttp-opts", "path")
	case kind == "ss":
		host, path = inner(proxy, "plugin-opts", "host"), inner(proxy, "plugin-opts", "path")
	}

	if network == "tcp" {
		network = ""
	}
	host = asciiLower(host)

	if network == "" && sni == "" && host == "" && path == "" {
		return ""
	}

	return network + "|" + sni + "|" + host + "|" + path
}

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

	kind = asciiLower(kind)

	return NodeInfo{Name: name, Type: kind, Server: server, Port: port, Via: Via(kind, proxy)}, true
}

func listOf(raw any) []map[string]any {
	var out []map[string]any

	switch list := raw.(type) {
	case []map[string]any:
		out = list
	case []any:
		for _, item := range list {
			if proxy := mapOf(item); proxy != nil {
				out = append(out, proxy)
			}
		}
	}

	return out
}

// apply — узлы провайдера provider, какими их заводит ядро: без исключённых
// типов, первый из одноимённых, имя с приставками.
func (s shaping) apply(raw any, provider string) []NodeInfo {
	var out []NodeInfo
	seen := map[string]bool{}

	for _, proxy := range listOf(raw) {
		if len(s.excludeTypes) > 0 {
			kind, ok := proxy["type"].(string)
			if !ok {
				continue
			}

			excluded := false
			for _, t := range s.excludeTypes {
				excluded = excluded || strings.EqualFold(t, kind)
			}
			if excluded {
				continue
			}
		}

		name, ok := proxy["name"].(string)
		if !ok || seen[name] {
			continue
		}
		seen[name] = true

		if info, ok := nodeOf(proxy); ok {
			info.Name = s.prefix + name + s.suffix
			info.Provider = provider
			out = append(out, info)
		}
	}

	return out
}

// shapingOf — как провайдер переделывает узлы; false — имён, какими их заведёт
// ядро, не повторить: proxy-name и override-expr переписывают их по-своему.
func shapingOf(provider map[string]any) (shaping, bool) {
	text := func(m map[string]any, key string) (string, bool) {
		value, found := fieldOf(m, key, false)
		if !found {
			return "", true
		}

		text, ok := value.(string)

		return text, ok
	}

	exclude, ok := text(provider, "exclude-type")
	if !ok {
		return shaping{}, false
	}

	var out shaping
	for _, t := range strings.Split(exclude, "|") {
		if t != "" {
			out.excludeTypes = append(out.excludeTypes, t)
		}
	}

	if raw, found := fieldOf(provider, "override", false); found {
		over := mapOf(raw)
		if over == nil {
			return shaping{}, false
		}

		for _, key := range []string{"proxy-name", "override-expr"} {
			value, used := fieldOf(over, key, false)
			if list, isList := value.([]any); used && (!isList || len(list) > 0) {
				return shaping{}, false
			}
		}

		if out.prefix, ok = text(over, "additional-prefix"); !ok {
			return shaping{}, false
		}
		if out.suffix, ok = text(over, "additional-suffix"); !ok {
			return shaping{}, false
		}
	}

	return out, true
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
// у скачиваемых path уже абсолютный. По порядку имён провайдеров: одноимённые
// узлы разных провайдеров решаются одинаково при каждом разборе.
func ProvidersOf(raw map[string]map[string]any) []ProviderSource {
	names := make([]string, 0, len(raw))
	for name := range raw {
		names = append(names, name)
	}
	sort.Strings(names)

	var out []ProviderSource

	for _, name := range names {
		provider := raw[name]

		shape, ok := shapingOf(provider)
		if !ok {
			continue
		}

		payload, _ := fieldOf(provider, "payload", false)
		kind, _ := fieldOf(provider, "type", false)
		if text, _ := kind.(string); strings.EqualFold(text, "inline") {
			out = append(out, ProviderSource{Name: name, Inline: shape.apply(payload, name), shape: shape})

			continue
		}

		if path, _ := provider["path"].(string); path != "" {
			out = append(out, ProviderSource{Name: name, Path: path, shape: shape, fallback: shape.apply(payload, name)})
		}
	}

	return out
}

// providerNodes — узлы провайдера; файл перечитывается, когда менялся, и
// молча пропускается, пока ядро его не скачало. Где файл и запасной набор
// расходятся адресом, узла нет: какой из двух сейчас у ядра, не узнать.
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

	if !ok || !cached.modified.Equal(stat.ModTime()) {
		var proxies []map[string]any

		if text, err := os.ReadFile(source.Path); err == nil {
			var doc struct {
				Proxies []any `yaml:"proxies"`
			}
			if yaml.Unmarshal(text, &doc) == nil {
				proxies = listOf(doc.Proxies)
			}
		}

		cached = providerFile{modified: stat.ModTime(), proxies: proxies}

		providerMu.Lock()
		providerFiles[source.Path] = cached
		providerMu.Unlock()
	}

	listed := source.shape.apply(cached.proxies, source.Name)
	if len(source.fallback) == 0 {
		return listed
	}

	other := make(map[string]NodeInfo, len(source.fallback))
	for _, info := range source.fallback {
		other[info.Name] = info
	}

	var out []NodeInfo
	for _, info := range listed {
		if was, found := other[info.Name]; found && was != info {
			continue
		}
		out = append(out, info)
	}

	return out
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
