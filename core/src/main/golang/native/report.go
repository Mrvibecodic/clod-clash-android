package main

//#include "bridge.h"
import "C"

import (
	"encoding/json"

	"cfa/native/config"
	"cfa/native/report"

	"github.com/metacubex/mihomo/log"
)

// reportRequest — что служба просит у сборщика отчёта прослойке.
type reportRequest struct {
	Op string `json:"op"`
	// Файл накопленного подписки; пусто — сбор выключен.
	Store string `json:"store"`
	Net   string `json:"net"`
	Kind  string `json:"kind"`
	// Момент смены сети, мс.
	At int64 `json:"at"`
	// Папка подписки: узлы без туннеля, ключ прослойки и запасной адрес.
	Path     string                   `json:"path"`
	URL      string                   `json:"url"`
	Verdicts map[string]report.Freeze `json:"verdicts"`
}

//export clientReport
func clientReport(request C.c_string) {
	defer guard("clientReport", func() {})()

	var req reportRequest
	if err := json.Unmarshal([]byte(C.GoString(request)), &req); err != nil {
		log.Warnln("[Report] bad request: %s", err.Error())

		return
	}

	switch req.Op {
	case "target":
		report.SetTarget(req.Store)
	case "network":
		report.NetworkChanged(req.Net, req.Kind, req.At)
	case "freeze":
		if req.Store == "" || req.Net == "" {
			return
		}

		known := report.LoadedNodes()
		if req.Path != "" {
			rawCfg, err := config.UnmarshalAndPatch(req.Path)
			if err != nil {
				log.Warnln("[Report] the subscription nodes could not be read: %s", err.Error())

				return
			}

			known = report.AllNodes(report.NodesOf(rawCfg.Proxy), report.ProvidersOf(rawCfg.ProxyProvider))
		}

		report.RecordFreeze(req.Store, report.PlaceFor(req.Net, req.Kind), known, req.Verdicts)
	case "send":
		if req.Store != "" && req.URL != "" && req.Path != "" {
			config.SendReport(req.Store, req.URL, req.Path)
		}
	}
}
