package panel

import (
	"encoding/json"
	"os"
	P "path"
)

type InboundPrefs struct {
	MixedPort int `json:"mixedPort,omitempty"`
	HttpPort  int `json:"httpPort,omitempty"`
}

const inboundFileName = "inbound.json"

func inboundPath(dir string) string {
	return P.Join(dir, inboundFileName)
}

func (i InboundPrefs) IsEmpty() bool {
	return i.MixedPort == 0 && i.HttpPort == 0
}

func (i InboundPrefs) LocalProxyPort() int {
	if i.MixedPort != 0 {
		return i.MixedPort
	}

	return i.HttpPort
}

func ReadInboundPrefs(dir string) InboundPrefs {
	var prefs InboundPrefs

	bytes, err := os.ReadFile(inboundPath(dir))
	if err != nil {
		return prefs
	}

	_ = json.Unmarshal(bytes, &prefs)

	return prefs
}

func WriteInboundPrefs(dir string, prefs InboundPrefs) {
	if prefs.IsEmpty() {
		_ = os.Remove(inboundPath(dir))

		return
	}

	writeJSON(inboundPath(dir), &prefs)
}
