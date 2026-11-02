package overlay

import (
	"bytes"
	"encoding/json"
	"slices"
	"strings"

	"cfa/native/config/panel"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/config"
)

const (
	GroupMode     = "mode"
	GroupLog      = "log"
	GroupPorts    = "ports"
	GroupDNS      = "dns"
	GroupFeatures = "features"
)

const ModeChoice = "mode.choice"

const modeKey = "mode"

type Line struct {
	Key     string   `json:"key"`
	Now     string   `json:"now"`
	Becomes string   `json:"becomes"`
	Reset   []string `json:"reset"`
}

type Group struct {
	ID    string `json:"id"`
	Lines []Line `json:"lines"`
}

type Values struct {
	Groups []Group `json:"groups"`
	Stack  string  `json:"stack,omitempty"`
}

type Mode struct {
	Persist *string
	Choice  *string
	Locked  bool
}

type side struct {
	cfg       *config.RawConfig
	dnsEnable bool
}

type field struct {
	group    string
	key      string
	path     string
	template string
	fold     bool
	value    func(s side) any
}

func (f field) own() string {
	if f.path != "" {
		return f.path
	}

	return f.key
}

func (f field) inTemplate() string {
	if f.template != "" {
		return f.template
	}

	return f.own()
}

var generalFields = []field{
	{group: GroupLog, key: "log-level", value: func(s side) any { return s.cfg.LogLevel }},
	{group: GroupLog, key: "unified-delay", value: func(s side) any { return s.cfg.UnifiedDelay }},

	{group: GroupPorts, key: "port", value: func(s side) any { return s.cfg.Port }},
	{group: GroupPorts, key: "socks-port", value: func(s side) any { return s.cfg.SocksPort }},
	{group: GroupPorts, key: "redir-port", value: func(s side) any { return s.cfg.RedirPort }},
	{group: GroupPorts, key: "tproxy-port", value: func(s side) any { return s.cfg.TProxyPort }},
	{group: GroupPorts, key: "mixed-port", value: func(s side) any { return s.cfg.MixedPort }},
	{group: GroupPorts, key: "allow-lan", value: func(s side) any { return s.cfg.AllowLan }},
	{group: GroupPorts, key: "ipv6", value: func(s side) any { return s.cfg.IPv6 }},
	{group: GroupPorts, key: "bind-address", value: func(s side) any { return s.cfg.BindAddress }},
	{group: GroupPorts, key: "authentication", value: func(s side) any { return s.cfg.Authentication }},
}

var featureFields = []field{
	{group: GroupFeatures, key: "tcp-concurrent", value: func(s side) any { return s.cfg.TCPConcurrent }},
	{group: GroupFeatures, key: "find-process-mode", value: func(s side) any { return s.cfg.FindProcessMode }},
	{group: GroupFeatures, key: "geodata-mode", value: func(s side) any { return s.cfg.GeodataMode }},
	{group: GroupFeatures, key: "sniffer.enable", value: func(s side) any { return s.cfg.Sniffer.Enable }},
	{group: GroupFeatures, key: "sniffer.force-dns-mapping", value: func(s side) any { return s.cfg.Sniffer.ForceDnsMapping }},
	{group: GroupFeatures, key: "sniffer.parse-pure-ip", value: func(s side) any { return s.cfg.Sniffer.ParsePureIp }},
	{group: GroupFeatures, key: "sniffer.override-destination", value: func(s side) any { return s.cfg.Sniffer.OverrideDest }},
	{group: GroupFeatures, key: "sniffer.force-domain", value: func(s side) any { return s.cfg.Sniffer.ForceDomain }},
	{group: GroupFeatures, key: "sniffer.skip-domain", value: func(s side) any { return s.cfg.Sniffer.SkipDomain }},
	{group: GroupFeatures, key: "sniffer.skip-src-address", value: func(s side) any { return s.cfg.Sniffer.SkipSrcAddress }},
	{group: GroupFeatures, key: "sniffer.skip-dst-address", value: func(s side) any { return s.cfg.Sniffer.SkipDstAddress }},
}

var fields = slices.Concat(generalFields, dnsFields, featureFields)

func lookup(tree map[string]any, key string, fold bool) bool {
	var node any = tree

	parts := strings.Split(key, ".")

	for i, part := range parts {
		object, ok := node.(map[string]any)
		if !ok {
			return false
		}

		node = object[part]

		if fold && i == len(parts)-1 {
			for name, value := range object {
				if strings.EqualFold(name, part) && value != nil {
					node = value
				}
			}
		}
	}

	return node != nil
}

func stackNamed(tree map[string]any) bool {
	if lookup(tree, "tun.stack", false) {
		return true
	}

	listeners, _ := tree["listeners"].([]any)

	for _, listener := range listeners {
		if mapping, ok := listener.(map[string]any); ok && mapping["type"] == "tun" && mapping["stack"] != nil {
			return true
		}
	}

	return false
}

func jsonText(value any) (string, error) {
	data, err := json.Marshal(value)

	return string(data), err
}

func quiet(string, ...any) {}

func resolve(cfg *config.RawConfig, slots ...string) side {
	s := side{cfg: cfg, dnsEnable: cfg.DNS.Enable}

	DefaultMixedPort(cfg, slots...)
	Dns(cfg, quiet, slots...)

	return s
}

func modeLine(tree map[string]any, subscription *config.RawConfig, mode Mode) (*Line, error) {
	if mode.Locked || !lookup(tree, modeKey, false) {
		return nil, nil
	}

	template, _ := panel.ResolveMode(subscription.Mode.String(), nil, nil, false)
	now, _ := panel.ResolveMode(subscription.Mode.String(), mode.Persist, mode.Choice, false)

	if now == template {
		return nil, nil
	}

	line := Line{Key: modeKey}

	if mode.Choice != nil && *mode.Choice != template {
		line.Reset = append(line.Reset, ModeChoice)
	}

	if mode.Persist != nil && *mode.Persist != template {
		line.Reset = append(line.Reset, modeKey)
	}

	var err error

	if line.Now, err = jsonText(now); err != nil {
		return nil, err
	}

	if line.Becomes, err = jsonText(template); err != nil {
		return nil, err
	}

	return &line, nil
}

func Differences(template []byte, persist string, mode Mode) (Values, error) {
	values := Values{Groups: []Group{}}

	data, err := age.DecryptBytes(template)
	if err != nil {
		return values, err
	}

	var tree map[string]any
	if err := yaml.Unmarshal(data, &tree); err != nil {
		return values, err
	}

	var own map[string]any
	if err := json.Unmarshal([]byte(persist), &own); err != nil {
		return values, err
	}

	subset := map[string]any{}
	for _, top := range []string{modeKey, "clash-for-android", "tun", "listeners"} {
		if value, ok := tree[top]; ok {
			subset[top] = value
		}
	}
	for _, f := range fields {
		top, _, _ := strings.Cut(f.key, ".")
		if value, ok := tree[top]; ok {
			subset[top] = value
		}
	}

	doc, err := yaml.Marshal(subset)
	if err != nil {
		return values, err
	}

	subscription, err := config.UnmarshalRawConfig(doc)
	if err != nil {
		return values, err
	}

	effective, err := config.UnmarshalRawConfig(doc)
	if err != nil {
		return values, err
	}

	if stackNamed(tree) {
		values.Stack = panel.TemplateStack(subscription.Tun.Enable, subscription.Tun.Stack.String(), subscription.Listeners)
	}

	line, err := modeLine(tree, subscription, mode)
	if err != nil {
		return values, err
	}

	if line != nil {
		values.Groups = append(values.Groups, Group{ID: GroupMode, Lines: []Line{*line}})
	}

	Apply(effective, persist, "{}", quiet)

	now := resolve(effective, persist)

	for start := 0; start < len(fields); {
		end := start
		for end < len(fields) && fields[end].group == fields[start].group {
			end++
		}

		lines, err := groupLines(doc, persist, now, tree, own, fields[start:end])
		if err != nil {
			return values, err
		}

		if len(lines) > 0 {
			values.Groups = append(values.Groups, Group{ID: fields[start].group, Lines: lines})
		}

		start = end
	}

	return values, nil
}

// «Станет» — что получит ядро, когда строки группы сняты, а остальные свои
// настройки в силе. Совпавшая строка не снимается, и без неё «станет» считается заново
func groupLines(doc []byte, persist string, now side, tree, own map[string]any, group []field) ([]Line, error) {
	var shown []field

	for _, f := range group {
		if lookup(own, f.own(), false) && lookup(tree, f.inTemplate(), f.fold) {
			shown = append(shown, f)
		}
	}

	for len(shown) > 0 {
		var keys []string

		for _, f := range shown {
			if !slices.Contains(keys, f.own()) {
				keys = append(keys, f.own())
			}
		}

		cleared, err := Clear(persist, keys)
		if err != nil {
			return nil, err
		}

		cfg, err := config.UnmarshalRawConfig(doc)
		if err != nil {
			return nil, err
		}

		Apply(cfg, cleared, "{}", quiet)

		becomes := resolve(cfg, cleared)

		var lines []Line
		var kept []field

		for _, f := range shown {
			current, err := json.Marshal(f.value(now))
			if err != nil {
				return nil, err
			}

			next, err := json.Marshal(f.value(becomes))
			if err != nil {
				return nil, err
			}

			if bytes.Equal(current, next) {
				continue
			}

			kept = append(kept, f)
			lines = append(lines, Line{Key: f.key, Now: string(current), Becomes: string(next), Reset: []string{f.own()}})
		}

		if len(kept) == len(shown) {
			return lines, nil
		}

		shown = kept
	}

	return nil, nil
}
