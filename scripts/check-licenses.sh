#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
for f in LICENSE REUSE.toml deny.toml LICENSES/Apache-2.0.txt LICENSES/CC-BY-4.0.txt; do
  [[ -s "$root/$f" ]] || { echo "missing $f" >&2; exit 1; }
done
grep -q 'Apache-2.0' "$root/LICENSE" || { echo "LICENSE is not Apache-2.0" >&2; exit 1; }
command -v cargo-deny >/dev/null || { echo "cargo-deny is required" >&2; exit 1; }
(cd "$root" && cargo deny check)
command -v reuse >/dev/null || { echo "reuse is required" >&2; exit 1; }
(cd "$root" && reuse lint)
echo "license gate passed"
