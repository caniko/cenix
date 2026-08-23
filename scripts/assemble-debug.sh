#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
"$root/scripts/build-native.sh"
(cd "$root/android" && ./gradlew :app:assembleDebug :fixture:assembleDebug)
echo "debug apk: $root/android/app/build/outputs/apk/debug/app-debug.apk"
