#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then
  echo "ANDROID_SDK_ROOT is required" >&2
  exit 1
fi
adb="${ADB:-adb}"
avd="${CENIX_AVD:-cenix-api35}"
sysdir="$sdk/system-images/android-35/google_apis/x86_64"
blocker="$root/docs/emulator-blocker.md"
write_blocker() {
  cat > "$blocker" <<EOF
# Emulator blocker

Need: $1

Have: $2

This is not GrapheneOS and not Pixel 10 Pro XL (\`mustang\`).
EOF
  echo "wrote $blocker" >&2
}
if [[ ! -d "$sysdir" ]]; then
  write_blocker \
    "AOSP API 35 x86_64 system image at \$ANDROID_SDK_ROOT/system-images/android-35/google_apis/x86_64" \
    "SDK root \`$sdk\` with no API 35 google_apis x86_64 image. Use: nix develop .#emulator"
  exit 1
fi
emulator_bin="${EMULATOR:-$sdk/emulator/emulator}"
if [[ ! -x "$emulator_bin" ]]; then
  write_blocker "emulator binary at \$ANDROID_SDK_ROOT/emulator/emulator" "missing \`$emulator_bin\`"
  exit 1
fi
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
if ! "$emulator_bin" -list-avds | grep -qx "$avd"; then
  avdmanager="$(echo "$sdk"/cmdline-tools/*/bin/avdmanager | awk '{print $1}')"
  if [[ ! -x "$avdmanager" ]]; then
    write_blocker "avdmanager to create $avd" "no avdmanager under $sdk/cmdline-tools"
    exit 1
  fi
  echo no | "$avdmanager" create avd -f -n "$avd" -k "system-images;android-35;google_apis;x86_64" >/dev/null
fi
started=0
if ! "$adb" devices | awk 'NR>1 && $2=="device"{found=1} END{exit found?0:1}'; then
  "$emulator_bin" -avd "$avd" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect >/tmp/cenix-emulator.log 2>&1 &
  started=$!
  cleanup() { kill "$started" 2>/dev/null || true; }
  trap cleanup EXIT
  "$adb" wait-for-device
  for _ in $(seq 1 60); do
    if [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      break
    fi
    sleep 5
  done
  if [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
    write_blocker "booted AVD $avd" "emulator started but sys.boot_completed never became 1; see /tmp/cenix-emulator.log"
    exit 1
  fi
fi

dump_ui() {
  "$adb" shell uiautomator dump /sdcard/cenix-ui.xml >/dev/null
  "$adb" shell cat /sdcard/cenix-ui.xml
}

start_home() {
  "$adb" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.caniko.cenix/.HomeActivity
  sleep 2
  if ! "$adb" shell dumpsys activity activities | grep -q 'com.caniko.cenix/.HomeActivity'; then
    echo "HOME activity did not start" >&2
    exit 1
  fi
}

"$root/scripts/assemble-debug.sh"
"$root/scripts/audit-apk.sh"
"$root/scripts/install-debug.sh"
"$adb" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null
holders="$("$adb" shell cmd role get-role-holders android.app.role.HOME | tr -d '\r')"
echo "$holders" | grep -q 'com.caniko.cenix' || { echo "Cenix is not HOME role holder: $holders" >&2; exit 1; }
start_home
ui="$(dump_ui)"
echo "$ui" | grep -q 'Search apps' || { echo "search field missing from HOME UI" >&2; exit 1; }
echo "$ui" | grep -qi 'emergency mode' && { echo "native APK started in emergency" >&2; exit 1; }

"$adb" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
start_home
dump_ui | grep -q 'Cenix Fixture' || { echo "fixture app missing after install" >&2; exit 1; }
"$adb" uninstall com.caniko.cenix.fixture >/dev/null
start_home
dump_ui | grep -q 'Cenix Fixture' && { echo "fixture app still listed after uninstall" >&2; exit 1; }

"$adb" shell am start -n com.caniko.cenix/.HomeActivity --ez com.caniko.cenix.FORCE_NATIVE_FAILURE true
sleep 2
dump_ui | grep -qi 'emergency mode' || { echo "forced native failure did not show emergency" >&2; exit 1; }

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
(cd "$root/android" && ./gradlew :app:assembleDebug -PomitNative)
omit_apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
CENIX_OMIT_NATIVE=1 "$root/scripts/audit-apk.sh" "$omit_apk"
"$adb" install -r -t "$omit_apk"
start_home
dump_ui | grep -qi 'emergency mode' || { echo "no-native APK did not enter emergency" >&2; exit 1; }
dump_ui | grep -q 'Search apps' || { echo "emergency HOME lost search" >&2; exit 1; }

echo "emulator conformance: HOME, search, package callbacks, crash extra, no-native emergency"
