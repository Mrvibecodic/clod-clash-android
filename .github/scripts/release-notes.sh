#!/usr/bin/env bash
set -euo pipefail
NOTES=$(awk -v ver="## v$VERSION" '
  $0 == ver { grab = 1; next }
  /^## v/ || /^---/ { if (grab) exit }
  grab { print }
' UPDATELOG.md 2>/dev/null || true)
if [[ -z "${NOTES// }" ]]; then
  NOTES=$(git tag -l --format='%(contents)' "$TAG")
fi
if [[ -z "${NOTES// }" ]]; then
  NOTES="Вышла версия $VERSION."
fi
printf '%s\n' "$NOTES"
