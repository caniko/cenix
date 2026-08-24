#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
for f in LICENSE REUSE.toml deny.toml LICENSES/Apache-2.0.txt LICENSES/CC-BY-4.0.txt; do
  [[ -s "$root/$f" ]] || { echo "missing $f" >&2; exit 1; }
done
grep -q 'Apache-2.0' "$root/LICENSE" || { echo "LICENSE is not Apache-2.0" >&2; exit 1; }
if command -v cargo-deny >/dev/null && cargo deny --version >/dev/null 2>&1; then
  (cd "$root" && cargo deny check licenses)
elif command -v cargo >/dev/null && cargo deny --help >/dev/null 2>&1; then
  (cd "$root" && cargo deny check licenses)
else
  echo "cargo-deny not installed; LICENSE/REUSE files present"
fi
echo "license gate passed"
