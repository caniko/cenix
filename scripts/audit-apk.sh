#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
manifest="$root/android/app/src/main/AndroidManifest.xml"
apk="$(realpath "${1:-$root/android/app/build/outputs/apk/release/app-release-unsigned.apk}")"
args=("$manifest")
if [[ -f "$apk" ]]; then
  args+=("$apk")
fi
(cd "$root/tools" && bun src/audit-manifest.ts "${args[@]}")
