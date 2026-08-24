#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cargo build --manifest-path "$root/Cargo.toml" -p cenix-ffi --locked --offline
normalize_kt() {
  local f
  while IFS= read -r -d '' f; do
    sed -i 's/[[:space:]]\+$//' "$f"
    if [[ -s "$f" && "$(tail -c1 "$f" | wc -l)" -eq 0 ]]; then
      printf '\n' >> "$f"
    fi
  done < <(find "$1" -name '*.kt' -print0)
}
committed="$root/android/app/src/main/java/com/caniko/cenix/uniffi"
if [[ "$(cat "$committed/GENERATOR")" != "uniffi=0.29.5" ]]; then
  echo "UniFFI generator version drift" >&2
  exit 1
fi
if grep -q '[[:space:]]$' "$committed"/*.kt; then
  echo "trailing whitespace in committed UniFFI Kotlin" >&2
  exit 1
fi
cargo run --manifest-path "$root/Cargo.toml" -p uniffi-bindgen --locked --offline -- generate \
  --library "$root/target/debug/libcenix_ffi.so" \
  --language kotlin \
  --no-format \
  --out-dir "$tmp"
normalize_kt "$tmp/com/caniko/cenix/uniffi"
diff -ru --exclude=GENERATOR "$committed" "$tmp/com/caniko/cenix/uniffi"
echo "UniFFI Kotlin bindings match generated output"
