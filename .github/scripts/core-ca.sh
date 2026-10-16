#!/usr/bin/env bash
set -euo pipefail
cd "$GITHUB_WORKSPACE"
sudo apt-get update && sudo apt-get install -y ca-certificates
sudo update-ca-certificates
cp -f /etc/ssl/certs/ca-certificates.crt core/src/foss/golang/clash/component/ca/ca-certificates.crt
# Пустой или несостоявшийся бандл ядро не заметит — молча уедет на
# хранилище устройства.
test -s core/src/foss/golang/clash/component/ca/ca-certificates.crt
test "$(grep -c 'BEGIN CERTIFICATE' core/src/foss/golang/clash/component/ca/ca-certificates.crt)" -gt 100
