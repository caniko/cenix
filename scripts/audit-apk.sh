#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
manifest="$root/android/app/src/main/AndroidManifest.xml"
if grep -q 'android.permission.INTERNET' "$manifest"; then
  echo "INTERNET permission is forbidden" >&2
  exit 1
fi
if ! grep -q 'android.intent.category.HOME' "$manifest"; then
  echo "HOME category is required" >&2
  exit 1
fi
apk="${1:-$root/android/app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$apk" ]]; then
  echo "apk not found: $apk" >&2
  exit 1
fi
listing="$(unzip -l "$apk")"
echo "$listing" | grep -q 'libcenix_ffi.so' || { echo "libcenix_ffi.so missing" >&2; exit 1; }
echo "$listing" | grep -q 'libjnidispatch.so' || { echo "libjnidispatch.so missing" >&2; exit 1; }
if unzip -p "$apk" AndroidManifest.xml | strings | grep -q 'android.permission.INTERNET'; then
  echo "INTERNET permission string found in APK" >&2
  exit 1
fi
echo "apk audit passed: $apk"
