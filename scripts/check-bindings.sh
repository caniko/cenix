#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cargo build --manifest-path "$root/Cargo.toml" -p cenix-ffi
cargo run --manifest-path "$root/Cargo.toml" -p uniffi-bindgen -- generate \
  --library "$root/target/debug/libcenix_ffi.so" \
  --language kotlin \
  --no-format \
  --out-dir "$tmp"
diff -ru "$root/android/app/src/main/java/com/caniko/cenix/uniffi" "$tmp/com/caniko/cenix/uniffi"
echo "UniFFI Kotlin bindings match generated output"
