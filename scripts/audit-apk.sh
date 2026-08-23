#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
manifest="$root/android/app/src/main/AndroidManifest.xml"
if grep -q 'android.permission.INTERNET' "$manifest"; then
  echo "INTERNET permission is forbidden" >&2
  exit 1
fi
if grep -q 'QUERY_ALL_PACKAGES' "$manifest"; then
  echo "QUERY_ALL_PACKAGES is forbidden" >&2
  exit 1
fi
if ! grep -q 'android.intent.category.HOME' "$manifest"; then
  echo "HOME category is required" >&2
  exit 1
fi
exported="$(grep -c 'android:exported="true"' "$manifest" || true)"
if [[ "$exported" -ne 1 ]]; then
  echo "exactly one exported component is allowed (HOME), found $exported" >&2
  exit 1
fi
if grep -R --include='*.kt' --include='*.xml' -n 'WebView' "$root/android/app/src" | grep -v '/uniffi/'; then
  echo "WebView is forbidden" >&2
  exit 1
fi
apk="${1:-$root/android/app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$apk" ]]; then
  echo "apk not found: $apk" >&2
  exit 1
fi
listing="$(unzip -l "$apk")"
omit="${CENIX_OMIT_NATIVE:-0}"
if [[ "$omit" == "1" ]]; then
  if echo "$listing" | grep -q 'libcenix_ffi.so'; then
    echo "libcenix_ffi.so must be absent when CENIX_OMIT_NATIVE=1" >&2
    exit 1
  fi
else
  echo "$listing" | grep -q 'libcenix_ffi.so' || { echo "libcenix_ffi.so missing" >&2; exit 1; }
  echo "$listing" | grep -q 'libjnidispatch.so' || { echo "libjnidispatch.so missing" >&2; exit 1; }
fi
apk_strings="$(unzip -p "$apk" AndroidManifest.xml | strings || true)"
echo "$apk_strings" | grep -q 'android.permission.INTERNET' && { echo "INTERNET permission string found in APK" >&2; exit 1; }
echo "$apk_strings" | grep -q 'QUERY_ALL_PACKAGES' && { echo "QUERY_ALL_PACKAGES found in APK" >&2; exit 1; }
echo "apk audit passed: $apk"
