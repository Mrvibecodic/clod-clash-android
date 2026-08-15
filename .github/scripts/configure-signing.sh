#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error::$1"
  exit 1
}

for name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD RUNNER_TEMP; do
  [[ -n "${!name:-}" ]] || fail "Обязательный signing input $name не задан"
done

umask 077
keystore="$RUNNER_TEMP/release.keystore"
if ! printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$keystore"; then
  rm -f "$keystore"
  fail "KEYSTORE_BASE64 содержит некорректный base64"
fi
if [[ ! -s "$keystore" ]] ||
   ! keytool -list -keystore "$keystore" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  rm -f "$keystore"
  fail "Keystore, пароль хранилища или alias не прошёл проверку"
fi

{
  printf 'keystore.path=%s\n' "$keystore"
  printf 'keystore.password=%s\n' "$KEYSTORE_PASSWORD"
  printf 'key.alias=%s\n' "$KEY_ALIAS"
  printf 'key.password=%s\n' "$KEY_PASSWORD"
} > signing.properties
