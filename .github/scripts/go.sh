#!/usr/bin/env bash
# Официальный Go со сверкой суммы (setup-go сумму архива не сверяет). Форк
# MetaCubeX/go Android не нужен: его правки — только для Windows 7/8 и старого
# macOS; Android-правки Go — патчи .github/patch/.
#
# Руками здесь ничего не поднимается: ветка Go — из строки `go` в go.mod ядра
# клиента, выпуск — самый свежий в этой ветке, сумма — из списка go.dev.
# Исправления безопасности приходят сами.
set -euo pipefail

mod="$GITHUB_WORKSPACE/core/src/main/golang/go.mod"
line=$(awk '$1 == "go" { split($2, v, "."); print v[1] "." v[2]; exit }' "$mod")
[ -n "$line" ] || { echo "::error::нет строки go в $mod"; exit 1; }

read -r GO_VERSION GO_SHA256 < <(curl -fsSL 'https://go.dev/dl/?mode=json&include=all' | jq -r --arg p "go$line." '
  [.[] | select(.stable and (.version | startswith($p)))] | max_by(.version | ltrimstr($p) | tonumber)
  | .version + " " + (.files[] | select(.os == "linux" and .arch == "amd64" and .kind == "archive") | .sha256)') || true

[ -n "${GO_SHA256:-}" ] || { echo "::error::не удалось получить выпуск Go $line с go.dev"; exit 1; }

curl -fsSL -o "$RUNNER_TEMP/go.tgz" "https://go.dev/dl/$GO_VERSION.linux-amd64.tar.gz"
echo "$GO_SHA256  $RUNNER_TEMP/go.tgz" | sha256sum -c -
tar -C "$RUNNER_TEMP" -xzf "$RUNNER_TEMP/go.tgz"

echo "$RUNNER_TEMP/go/bin" >> "$GITHUB_PATH"
echo "GOROOT=$RUNNER_TEMP/go" >> "$GITHUB_ENV"
echo "GOTOOLCHAIN=local" >> "$GITHUB_ENV"
echo "GO_VERSION=$GO_VERSION" >> "$GITHUB_ENV"

# Ветка Go из go.mod вышла из поддержки — исправлений безопасности больше не
# будет; видно предупреждением в каждом прогоне.
if supported=$(curl -fsSL 'https://go.dev/dl/?mode=json') &&
  ! jq -e --arg p "go$line." 'any(.[]; .version | startswith($p))' <<< "$supported" >/dev/null; then
  echo "::warning::Go $line больше не поддерживается — поднять строку go в core/src/main/golang/go.mod"
fi
