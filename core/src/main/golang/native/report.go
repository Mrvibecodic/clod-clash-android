package main

//#include "bridge.h"
import "C"

import (
	"encoding/json"

	"cfa/native/app"
	"cfa/native/config"
	"cfa/native/report"

	"github.com/metacubex/mihomo/log"
)

// reportRequest — что служба просит у сборщика отчёта прослойке.
type reportRequest struct {
	Op string `json:"op"`
	// Соль ключа сети установки: Net приходит без неё и солится здесь.
	Salt string `json:"salt"`
	// Файл накопленного подписки; пусто — сбор выключен.
	Store string `json:"store"`
	Net   string `json:"net"`
	Kind  string `json:"kind"`
	// Момент смены сети, мс.
	At int64 `json:"at"`
	// Подписка, чьи это накопленное и итоги (target, freeze).
	UUID string `json:"uuid"`
	// Папка подписки: узлы без туннеля, ключ прослойки и запасной адрес.
	Path     string                   `json:"path"`
	URL      string                   `json:"url"`
	Verdicts map[string]report.Freeze `json:"verdicts"`
	// Карточка устройства на момент отправки (send); пустой HWID — без опознания.
	Hwid  string `json:"hwid"`
	OS    string `json:"os"`
	OSVer string `json:"osVer"`
	Model string `json:"model"`
}

//export clientReport
func clientReport(request C.c_string) {
	defer guard("clientReport", func() {})()

	var req reportRequest
	if err := json.Unmarshal([]byte(C.GoString(request)), &req); err != nil {
		log.Warnln("[Report] bad request: %s", err.Error())

		return
	}

	if req.Salt != "" {
		report.SetSalt(req.Salt)
	}

	req.Net = report.NetKey(req.Net)

	switch req.Op {
	case "target":
		report.SetTarget(req.Store, req.UUID)
	case "forget":
		report.Forget(req.Store)
	case "network":
		report.NetworkChanged(req.Net, req.Kind, req.At)
	case "freeze":
		if req.Store == "" || req.Net == "" {
			return
		}

		var known map[string]report.NodeInfo

		if req.Path == "" {
			var ours bool
			if known, ours = report.NodesFor(req.UUID); !ours {
				log.Debugln("[Report] 16-20 results dropped: the core runs another subscription")

				return
			}
		} else {
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
			config.SendReport(req.Store, req.URL, req.Path, app.CardHeaders(req.Hwid, req.OS, req.OSVer, req.Model))
		}
	}
}
