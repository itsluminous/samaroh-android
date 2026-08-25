#!/usr/bin/env bash
# Legal-hygiene check: fails (exit 1) if any third-party reference-product name appears
# anywhere in the repo. The denylist is embedded base64-encoded so this repo never
# contains the names in plain text; the scan excludes .git and this script itself.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SELF="scripts/$(basename "${BASH_SOURCE[0]}")"

DENYLIST_B64="a2hhdGFib29rCm9rY3JlZGl0CnZ5YXBhcgp2ZW51cmEKYmFucXVleApiYW5xdWV0Zmlyc3QKaGFsbGRlc2sKZ2FsYSBmbG93CnBsYW5uaW5nIHBvZAp0cmlwbGVzZWF0CnBlcmZlY3QgdmVudWUKZXZlbnQgdGVtcGxlCnNrZWRkYQpnb2xkaWUKZnJlc2hhCnNvcnRseQp2ZW51ZSBtYW5hZ2VyCnpvaG8K"

PATTERNS="$(printf '%s' "$DENYLIST_B64" | base64 -d)"

STATUS=0
HITS="$(cd "$REPO_ROOT" && grep -R -i -n -F \
  --exclude-dir=.git \
  --exclude-dir=node_modules \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  --exclude-dir=.kotlin \
  --exclude-dir=.idea \
  --exclude="$(basename "$SELF")" \
  --exclude="*.jar" \
  --exclude="*.log" \
  -f <(printf '%s\n' "$PATTERNS") . 2>/dev/null || true)"

if [[ -n "$HITS" ]]; then
  echo "LEGAL HYGIENE CHECK FAILED — forbidden reference-product name(s) found:" >&2
  echo "$HITS" >&2
  STATUS=1
else
  echo "Legal hygiene check OK — no forbidden names found."
fi

exit "$STATUS"
