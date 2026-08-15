#!/usr/bin/env bash
set -euo pipefail

readonly go_version='go1.26.7'
readonly archive_url='https://github.com/MetaCubeX/go/releases/download/build/go1.26.linux-amd64.tar.gz'
readonly archive_sha256='a1b96a03cf28be04c1770f17f019018b5ba71dff05396118f643ab13516a173f'
readonly archive="$RUNNER_TEMP/metacubex-go.tar.gz"
readonly install_root="$RUNNER_TEMP/metacubex-go"
readonly goroot="$install_root/go"

curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --output "$archive" "$archive_url"
printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum --check --status || {
  echo '::error::MetaCubeX Go archive checksum mismatch'
  exit 1
}

rm -rf "$install_root"
mkdir -p "$install_root"
tar --extract --gzip --file "$archive" --directory "$install_root" --strip-components=1
rm -f "$archive"

actual=$("$goroot/bin/go" version)
[[ "$actual" == "go version $go_version linux/amd64" ]] || {
  echo "::error::Unexpected Go toolchain version: $actual"
  exit 1
}

printf 'GOROOT=%s\n' "$goroot" >> "$GITHUB_ENV"
printf 'GOTOOLCHAIN=local\n' >> "$GITHUB_ENV"
printf '%s/bin\n' "$goroot" >> "$GITHUB_PATH"
