#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/android/app/src/main/java"
cargo build --manifest-path "$root/Cargo.toml" -p cenix-ffi --locked --offline
lib="$root/target/debug/libcenix_ffi.so"
if [[ ! -f "$lib" ]]; then
  echo "missing $lib" >&2
  exit 1
fi
cargo run --manifest-path "$root/Cargo.toml" -p uniffi-bindgen --locked --offline -- generate \
  --library "$lib" \
  --language kotlin \
  --no-format \
  --out-dir "$out"
# strip generator trailing whitespace; keep UniFFI semantics
while IFS= read -r -d '' f; do
  sed -i 's/[[:space:]]\+$//' "$f"
  sed -i -e :a -e '/^\n*$/{$d;N;ba;}' "$f"
  if [[ -s "$f" && "$(tail -c1 "$f" | wc -l)" -eq 0 ]]; then
    printf '\n' >> "$f"
  fi
done < <(find "$out/com/caniko/cenix/uniffi" -name '*.kt' -print0)
printf 'uniffi=0.29.5\n' >"$out/com/caniko/cenix/uniffi/GENERATOR"
echo "generated UniFFI Kotlin under $out/com/caniko/cenix/uniffi"
