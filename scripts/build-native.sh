#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}" ]]; then
  echo "ANDROID_NDK_HOME is required" >&2
  exit 1
fi
outdir="$root/android/app/src/main/jniLibs"
rm -rf "$outdir"
# Harbor cargo config forces host mold on android triples. cargo-ndk needs NDK lld.
env -u CARGO_HOME cargo ndk -t arm64-v8a -t x86_64 -o "$outdir" -P 35 \
  build -p cenix-ffi --release --locked --offline
echo "native libraries written to $outdir"
