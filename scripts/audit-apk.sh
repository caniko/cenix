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
if grep -R --include='*.kt' --include='*.xml' -nE 'WebView|cuscon|Cuscon|cenix_jni|FilterProtocol|NativeBridge|harbor-js' "$root/android" \
  | grep -v '/uniffi/' | grep -v '/build/' | grep -q .; then
  echo "forbidden dependency or leftover protocol reference" >&2
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
  echo "$listing" | grep -q 'lib/arm64-v8a/libcenix_ffi.so' || { echo "arm64-v8a libcenix_ffi.so missing" >&2; exit 1; }
  if [[ "$apk" != *release* ]]; then
    echo "$listing" | grep -q 'lib/x86_64/libcenix_ffi.so' || { echo "x86_64 libcenix_ffi.so missing" >&2; exit 1; }
  elif echo "$listing" | grep -q 'lib/x86_64/libcenix_ffi.so'; then
    echo "release APK must contain only the arm64-v8a Cenix library" >&2
    exit 1
  fi
fi
echo "$listing" | grep -qiE 'cenix_jni|harbor-js|cuscon' && { echo "obsolete artifact in APK listing" >&2; exit 1; }

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
aapt_bin="$(echo "$sdk"/build-tools/*/aapt | awk '{print $1}')"
if [[ ! -x "$aapt_bin" ]]; then
  aapt_bin="$(command -v aapt || true)"
fi
if [[ ! -x "${aapt_bin:-}" ]]; then
  echo "aapt is required to audit the merged manifest" >&2
  exit 1
fi
badging="$("$aapt_bin" dump badging "$apk")"
xmltree="$("$aapt_bin" dump xmltree "$apk" AndroidManifest.xml)"
echo "$xmltree" | grep -q 'android.permission.INTERNET' && { echo "INTERNET in merged manifest" >&2; exit 1; }
echo "$xmltree" | grep -q 'QUERY_ALL_PACKAGES' && { echo "QUERY_ALL_PACKAGES in merged manifest" >&2; exit 1; }
echo "$xmltree" | grep -q 'android.intent.category.HOME' || { echo "HOME missing from merged manifest" >&2; exit 1; }
echo "$badging" | grep -qiE 'cuscon|harbor-js|cenix_jni' && { echo "forbidden string in badging" >&2; exit 1; }
python3 - "$apk" <<'PY' || { echo "Play Services reference in APK" >&2; exit 1; }
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    bad = any(b"com/google/android/gms" in archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
raise SystemExit(1 if bad else 0)
PY
if [[ "$apk" == *release* ]]; then
  echo "$badging" | grep -q 'application-debuggable' && { echo "release APK is debuggable" >&2; exit 1; }
  apksigner_bin="$sdk/build-tools/35.0.0/apksigner"
  [[ -x "$apksigner_bin" ]] || { echo "apksigner 35.0.0 is required" >&2; exit 1; }
  signature="$($apksigner_bin verify --print-certs "$apk" 2>&1 || true)"
  echo "$signature" | grep -q 'CN=Android Debug' && { echo "release APK is debug-signed" >&2; exit 1; }
  if [[ "${CENIX_ALLOW_SIGNED_RELEASE:-0}" != "1" ]] && "$apksigner_bin" verify "$apk" >/dev/null 2>&1; then
    echo "production release must be unsigned before the external signing workflow" >&2
    exit 1
  fi
fi

if [[ "$omit" != "1" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  unzip -qo "$apk" 'lib/*/*.so' -d "$tmp"
  mapfile -t sos < <(find "$tmp/lib" -name 'libcenix_ffi.so' -o -name 'libjnidispatch.so')
  if [[ "${#sos[@]}" -eq 0 ]]; then
    echo "no native libraries extracted from $apk" >&2
    exit 1
  fi
  "$root/scripts/check-16k.sh" "${sos[@]}"
  [[ "$apk" != *release* ]] || "$root/scripts/check-native-symbols.sh" "$apk"
fi
echo "apk audit passed: $apk"
if [[ $# -eq 0 ]]; then
  release_apk="$root/android/app/build/outputs/apk/release/app-release-unsigned.apk"
  export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  (cd "$root/android" && ./gradlew --offline :app:assembleRelease)
  "$0" "$release_apk"
fi
