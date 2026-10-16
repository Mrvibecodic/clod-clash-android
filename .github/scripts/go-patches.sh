#!/usr/bin/env bash
set -euo pipefail
cd $(go env GOROOT)
for p in $GITHUB_WORKSPACE/.github/patch/*.patch; do patch --verbose -p 1 -F 0 < "$p"; done
# Стражи на оба патча: первый ДОБАВЛЯЕТ ветку, второй УДАЛЯЕТ две.
grep -q 'tailscale/tailscale/issues/13452' src/os/pidfd_linux.go
test "$(grep -c 'if use64bitsTimeOn32bits' src/runtime/os_linux32.go)" = "0"
