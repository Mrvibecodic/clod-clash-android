package panel

import (
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

func WriteInboundPrefs(dir string, prefs InboundPrefs) {
	if prefs.IsEmpty() {
		_ = os.Remove(inboundPath(dir))

		return
	}

	writeJSON(inboundPath(dir), &prefs)
}
