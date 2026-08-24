#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
manifest="$root/android/app/src/main/AndroidManifest.xml"
grep -q 'android.permission.INTERNET' "$manifest" && { echo "INTERNET permission" >&2; exit 1; }
grep -q 'QUERY_ALL_PACKAGES' "$manifest" && { echo "QUERY_ALL_PACKAGES" >&2; exit 1; }
if grep -R --include='*.kt' --include='*.xml' -nE 'WebView|PlayServices|com.google.android.gms' \
  "$root/android/app/src/main" | grep -v '/uniffi/' | grep -q .; then
  echo "proprietary or WebView reference" >&2
  exit 1
fi
echo "foss gate passed (emulator image is still google_apis unless .#emulator-aosp exists)"
