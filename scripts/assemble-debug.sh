#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
"$root/scripts/build-native.sh"
# The following offline release audit also needs the materialized lint engine.
(cd "$root/android" && ./gradlew --rerun-tasks :app:assembleDebug :app:lintDebug :fixture:assembleDebug :fixture-secondary:assembleDebug)
echo "debug apk: $root/android/app/build/outputs/apk/debug/app-debug.apk"
