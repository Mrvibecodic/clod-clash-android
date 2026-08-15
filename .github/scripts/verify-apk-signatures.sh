#!/usr/bin/env bash
set -euo pipefail

apk_directory=${1:?APK directory is required}
shopt -s nullglob
apks=("$apk_directory"/*.apk)
((${#apks[@]} > 0)) || { echo "::error::APK для проверки подписи не найдены"; exit 1; }

tools=("$ANDROID_HOME"/build-tools/*/apksigner)
((${#tools[@]} > 0)) || { echo "::error::apksigner не найден"; exit 1; }
mapfile -t tools < <(printf '%s\n' "${tools[@]}" | sort -V)
apksigner=${tools[-1]}

for apk in "${apks[@]}"; do
  "$apksigner" verify "$apk"
done
