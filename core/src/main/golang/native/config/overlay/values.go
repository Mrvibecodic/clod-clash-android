package overlay

import (
	"encoding/json"
	"strings"

	"cfa/native/config/panel"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/config"
)

const GroupMode = "mode"

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
}

type Mode struct {
	Persist *string
	Choice  *string
	Locked  bool
}

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

func jsonText(value any) (string, error) {
	data, err := json.Marshal(value)

	return string(data), err
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

	if err := json.Unmarshal([]byte(persist), new(map[string]any)); err != nil {
		return values, err
	}

	subset := map[string]any{}
	if value, ok := tree[modeKey]; ok {
		subset[modeKey] = value
	}

	doc, err := yaml.Marshal(subset)
	if err != nil {
		return values, err
	}

	subscription, err := config.UnmarshalRawConfig(doc)
	if err != nil {
		return values, err
	}

	line, err := modeLine(tree, subscription, mode)
	if err != nil {
		return values, err
	}

	if line != nil {
		values.Groups = append(values.Groups, Group{ID: GroupMode, Lines: []Line{*line}})
	}

	return values, nil
}
