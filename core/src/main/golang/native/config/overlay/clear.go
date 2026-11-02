package overlay

import (
	"encoding/json"
	"slices"
	"strings"
)

func remove(object map[string]json.RawMessage, path []string) error {
	raw, ok := object[path[0]]
	if !ok {
		return nil
	}

	if len(path) == 1 {
		delete(object, path[0])

		return nil
	}

	var child map[string]json.RawMessage
	if err := json.Unmarshal(raw, &child); err != nil {
		return err
	}

	if child == nil {
		return nil
	}

	if err := remove(child, path[1:]); err != nil {
		return err
	}

	if len(child) == 0 {
		delete(object, path[0])

		return nil
	}

	data, err := json.Marshal(child)
	if err != nil {
		return err
	}

	object[path[0]] = data

	return nil
}

func Clear(persist string, keys []string) (string, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal([]byte(persist), &root); err != nil {
		return "", err
	}

	if root == nil {
		root = map[string]json.RawMessage{}
	}

	for _, key := range keys {
		if err := remove(root, strings.Split(key, ".")); err != nil {
			return "", err
		}
	}

	data, err := json.Marshal(root)

	return string(data), err
}

func Resettable(keys []string) []string {
	var result []string

	for _, key := range keys {
		if key == modeKey || slices.ContainsFunc(fields, func(f field) bool { return f.own() == key }) {
			result = append(result, key)
		}
	}

	return result
}
