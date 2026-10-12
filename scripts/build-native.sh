#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if [[ -z "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}" ]]; then
  echo "ANDROID_NDK_HOME is required" >&2
  exit 1
fi
outdir="$root/android/app/src/main/jniLibs"
rm -rf "$outdir"
# cargo-ndk needs NDK lld rather than the shell's host linker configuration.
# Keep the prefetched registry/Git caches available for the locked offline build.
source_cargo_home="${CARGO_HOME:-$HOME/.cargo}"
source_cargo_home="$(cd "$source_cargo_home" && pwd)"
native_cargo_home="$(mktemp -d)"
trap 'rm -rf "$native_cargo_home"' EXIT
for cache in registry git; do
  if [[ -d "$source_cargo_home/$cache" ]]; then
    ln -s "$source_cargo_home/$cache" "$native_cargo_home/$cache"
  fi
done
# Rust records dependency paths in panic locations even in release binaries.
# Remap the randomized config-free home so independent offline builds match.
remap="--remap-path-prefix=$native_cargo_home=/cargo-home"
if [[ -v CARGO_ENCODED_RUSTFLAGS ]]; then
  export CARGO_ENCODED_RUSTFLAGS="${CARGO_ENCODED_RUSTFLAGS:+$CARGO_ENCODED_RUSTFLAGS$'\x1f'}$remap"
else
  export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }$remap"
fi
CARGO_HOME="$native_cargo_home" cargo ndk -t arm64-v8a -t x86_64 -o "$outdir" -P 35 \
  build -p cenix-ffi --release --locked --offline
echo "native libraries written to $outdir"
