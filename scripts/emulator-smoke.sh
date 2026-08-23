#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
adb="${ADB:-adb}"
avd="${CENIX_AVD:-cenix-api35}"
sysdir="$sdk/system-images/android-35/google_apis/x86_64"
echo "emulator suite must run through: nix develop .#emulator"
echo "this AOSP API 35 google_apis x86_64 image is not GrapheneOS and not Pixel 10 Pro XL (mustang)"
if [[ -z "$sdk" || ! -d "$sysdir" ]]; then
  echo "missing API 35 system image; use: nix develop .#emulator" >&2
  exit 1
fi
emulator_bin="${EMULATOR:-$sdk/emulator/emulator}"
if [[ ! -x "$emulator_bin" ]]; then
  echo "missing emulator binary; use: nix develop .#emulator" >&2
  exit 1
fi
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
if ! "$emulator_bin" -list-avds | grep -qx "$avd"; then
  avdmanager="$(echo "$sdk"/cmdline-tools/*/bin/avdmanager | awk '{print $1}')"
  echo no | "$avdmanager" create avd -f -n "$avd" -k "system-images;android-35;google_apis;x86_64" >/dev/null
fi
avd_serial() {
  local serial name
  while read -r serial _; do
    [[ -z "${serial:-}" ]] && continue
    name="$("$adb" -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -n1 || true)"
    if [[ "$name" == "$avd" ]]; then
      printf '%s\n' "$serial"
      return 0
    fi
  done < <("$adb" devices | awk 'NR>1 && $2=="device"{print $1}')
  return 1
}
started=0
serial="$(avd_serial || true)"
if [[ -z "$serial" ]]; then
  "$emulator_bin" -avd "$avd" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect >/tmp/cenix-emulator.log 2>&1 &
  started=$!
  cleanup() { kill "$started" 2>/dev/null || true; }
  trap cleanup EXIT
  for _ in $(seq 1 90); do
    serial="$(avd_serial || true)"
    [[ -n "$serial" ]] && break
    sleep 2
  done
  [[ -n "$serial" ]] || { echo "AVD $avd never appeared" >&2; exit 1; }
fi
export ANDROID_SERIAL="$serial"
for _ in $(seq 1 60); do
  [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 5
done
[[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] || {
  echo "boot did not complete on $serial" >&2
  exit 1
}
"$root/scripts/assemble-debug.sh"
"$root/scripts/audit-apk.sh"
"$root/scripts/install-debug.sh"
"$adb" -s "$serial" shell input keyevent KEYCODE_HOME
sleep 2
if ! "$adb" -s "$serial" shell dumpsys activity activities | grep -q 'com.caniko.cenix/.HomeActivity'; then
  echo "HOME activity did not start on $serial" >&2
  exit 1
fi
echo "emulator smoke: HOME started on $serial"
