#!/usr/bin/env bash
# Патчи Android живут коммитами в ветке android репозитория Mrvibecodic/clod-core,
# submodule смотрит туда. Маркеры из тела каждой правки проверяют, что подтянуто
# именно ядро с патчами: нет маркера — правки в ядре нет.
set -euo pipefail
cd "$GITHUB_WORKSPACE/core/src/foss/golang/clash"
grep -q ProbeHolding adapter/adapter.go
grep -qF 'ctx.Err() == context.Canceled' adapter/adapter.go
grep -q ProbeHolding adapter/outboundgroup/groupbase.go
grep -q ProbeFreezeGap constant/probehold.go
# Общие правки проб из main clod-core: повторная проба рядом с первой,
# штраф нестабильным узлам в url-test, шаг между пробами к одному хосту
grep -q HeldProbe constant/probeconfirm.go
grep -q probeHedgeDelay adapter/provider/healthcheck.go
grep -q flakyPenalty adapter/outboundgroup/urltest.go
grep -q ProbePace constant/probepace.go
grep -Eq 'expectedStatus +string' adapter/outboundgroup/selector.go
grep -Eq '"expectedStatus": +s\.expectedStatus,' adapter/outboundgroup/selector.go
grep -Eq 'expectedStatus: +option\.ExpectedStatus,' adapter/outboundgroup/selector.go
grep -q ResetNetwork adapter/outbound/base.go
grep -q ProbeWake adapter/outbound/wake.go
grep -qF 'h.wake.renew(' adapter/outbound/hysteria2.go
grep -qF 't.wake.renew(' adapter/outbound/tuic.go
grep -q ErrRedirectDowngrade component/http/http.go
grep -qF 'req.URL.Scheme != "https"' component/http/http.go
grep -qF 'return ErrRedirectDowngrade' component/http/http.go
grep -A1 -F 'loadProvider(cfg.RuleProviders)' hub/executor/executor.go | grep -qF 'tunnel.OnRunning()'
grep -A1 -F 'tunnel.OnRunning()' hub/executor/executor.go | grep -qF 'runtime.GC()'
# Проверка 16–20: обрыв по пути — «неясно», отпечаток без имени сервера и Host
grep -A3 -F 'case ctx.Err() == nil:' adapter/download.go | grep -qF 'return DownloadUnknown'
grep -q disguiseKeys adapter/fingerprint.go
# Автовыбор: узел, переставший отвечать, замечается по трафику, после
# оживления возвращается в выбор; транспорты отставляют старые соединения
grep -q watchedConn adapter/outboundgroup/silence.go
grep -q cutPause adapter/outboundgroup/silence.go
grep -qF 'проверен из-за' adapter/outboundgroup/silence.go
grep -q kindOf adapter/outboundgroup/kind.go
grep -q nodeOf adapter/parser.go
grep -q recheckControlPauses adapter/outboundgroup/recheck.go
grep -q probationOf adapter/outboundgroup/recheck.go
grep -q lookAgain adapter/provider/healthcheck.go
grep -q awaitProbeHold adapter/provider/healthcheck.go
grep -q roundSilenceLimit adapter/provider/healthcheck.go
grep -q FreshConns adapter/provider/fresh.go
grep -q CloseRetired transport/gun/retire.go
grep -A3 -F 'func (c *Client) ResetNetwork()' transport/xhttp/client.go | grep -qF 'c.CloseRetired()'
grep -A20 -F 'func (t *PoolClient) ResetNetwork()' transport/tuic/pool_client.go | grep -qF 't.CloseRetired()'
