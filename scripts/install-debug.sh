#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
"$root/scripts/assemble-debug.sh"
adb="${ADB:-adb}"
"$adb" install -r -t "$root/android/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk" ]]; then
  "$adb" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
fi
echo "installed Cenix debug APK"
