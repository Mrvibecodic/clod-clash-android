#!/usr/bin/env bash
# Ядро свежее, поэтому и его replace-правила (utls, protobuf) берутся из его
# go.mod: зависимостям Go их не передаёт, а без копии в модуле клиента
# соберётся стоковый utls. Версии остальных зависимостей до требований
# ядра поднимает сам Go (GOFLAGS=-mod=mod у задачи).
set -euo pipefail
cd "$GITHUB_WORKSPACE"
rules=$(awk '$1 == "replace" && NF == 5 {print $2 "=" $4 "@" $5}' core/src/foss/golang/clash/go.mod)
echo "$rules" | grep -q '^github.com/metacubex/utls='
for r in $rules; do (cd core/src/main/golang && go mod edit -replace="$r"); done
