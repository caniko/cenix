#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cargo build --manifest-path "$root/Cargo.toml" -p cenix-ffi
committed="$root/android/app/src/main/java/com/caniko/cenix/uniffi"
if grep -q '[[:space:]]$' "$committed"/*.kt; then
  echo "trailing whitespace in committed UniFFI Kotlin" >&2
  exit 1
fi
cargo run --manifest-path "$root/Cargo.toml" -p uniffi-bindgen -- generate \
  --library "$root/target/debug/libcenix_ffi.so" \
  --language kotlin \
  --no-format \
  --out-dir "$tmp"
find "$tmp/com/caniko/cenix/uniffi" -name '*.kt' -exec sed -i 's/[[:space:]]\+$//' {} +
diff -ru "$committed" "$tmp/com/caniko/cenix/uniffi"
echo "UniFFI Kotlin bindings match generated output"
