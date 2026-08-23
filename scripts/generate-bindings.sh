#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/android/app/src/main/java"
cargo build --manifest-path "$root/Cargo.toml" -p cenix-ffi
lib="$root/target/debug/libcenix_ffi.so"
if [[ ! -f "$lib" ]]; then
  echo "missing $lib" >&2
  exit 1
fi
cargo run --manifest-path "$root/Cargo.toml" -p uniffi-bindgen -- generate \
  --library "$lib" \
  --language kotlin \
  --no-format \
  --out-dir "$out"
find "$out/com/caniko/cenix/uniffi" -name '*.kt' -exec sed -i 's/[[:space:]]\+$//' {} +
echo "generated UniFFI Kotlin under $out/com/caniko/cenix/uniffi"
