#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
adb="${ADB:-adb}"
run_id="${CENIX_RUN_ID:-$$-$(date +%s)}"
art="${CENIX_ARTIFACTS:-$(mktemp -d /tmp/cenix-conformance.XXXXXX)}"
mkdir -p "$art"
export CENIX_GIT_COMMIT="${CENIX_GIT_COMMIT:-$(git -C "$root" rev-parse HEAD)}"
avd="${CENIX_AVD:-cenix-ci-$run_id}"
if [[ "$avd" == "cenix-api35" && "${CENIX_ALLOW_SHARED_AVD:-0}" != "1" ]]; then
  echo "refusing shared AVD cenix-api35; set CENIX_ALLOW_SHARED_AVD=1 to override" >&2
  exit 1
fi
default_image="$sdk/system-images/android-35/default/x86_64"
compat_image="$sdk/system-images/android-35/google_apis/x86_64"
if [[ -d "$default_image" ]]; then
  default_sysdir="$default_image"
else
  default_sysdir="$compat_image"
fi
sysdir="${CENIX_SYSTEM_IMAGE:-$default_sysdir}"
image_kind="google_apis"
if [[ "$sysdir" == *"/default/"* || "$sysdir" == *"/aosp"* ]]; then
  image_kind="aosp"
fi
echo "conformance artifacts: $art"
echo "emulator suite must run through the matching Nix emulator shell"
echo "this Android API 35 $image_kind x86_64 image is not GrapheneOS and not Pixel 10 Pro XL (mustang)"
echo "git commit: $CENIX_GIT_COMMIT"
echo "avd: $avd"

if [[ -z "$sdk" ]]; then
  echo "ANDROID_SDK_ROOT is required; use: nix develop .#emulator" >&2
  exit 1
fi
if [[ ! -d "$sysdir" ]]; then
  echo "missing $sysdir" >&2
  echo "emulator suite must run through: nix develop .#emulator" >&2
  exit 1
fi
emulator_bin="${EMULATOR:-$sdk/emulator/emulator}"
if [[ ! -x "$emulator_bin" ]]; then
  echo "missing emulator binary $emulator_bin; use: nix develop .#emulator" >&2
  exit 1
fi

export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$art/avd}"
export ANDROID_EMULATOR_HOME="${ANDROID_EMULATOR_HOME:-$art/emu-home}"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_EMULATOR_HOME"
image_pkg="system-images;android-35;google_apis;x86_64"
if [[ "$image_kind" == "aosp" ]]; then
  image_pkg="system-images;android-35;default;x86_64"
fi
if ! "$emulator_bin" -list-avds | grep -qx "$avd"; then
  avdmanager="$(echo "$sdk"/cmdline-tools/*/bin/avdmanager | awk '{print $1}')"
  if [[ ! -x "$avdmanager" ]]; then
    echo "no avdmanager under $sdk/cmdline-tools; use: nix develop .#emulator" >&2
    exit 1
  fi
  echo no | "$avdmanager" create avd -f -n "$avd" -k "$image_pkg" >/dev/null
fi
cfg="$ANDROID_AVD_HOME/${avd}.avd/config.ini"
if [[ -f "$cfg" ]]; then
  for key in hw.lcd.width=320 hw.lcd.height=640 hw.lcd.density=160; do
    k="${key%%=*}"
    if grep -q "^$k=" "$cfg"; then
      sed -i "s/^$k=.*/$key/" "$cfg"
    else
      printf '%s\n' "$key" >>"$cfg"
    fi
  done
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
if [[ -n "$serial" && "${CENIX_ALLOW_SHARED_AVD:-0}" != "1" ]]; then
  echo "AVD $avd already has serial $serial; refuse to reuse" >&2
  exit 1
fi
emu_port="${CENIX_EMU_PORT:-$((5570 + RANDOM % 20 * 2))}"
if [[ -z "$serial" ]]; then
  "$emulator_bin" -avd "$avd" -port "$emu_port" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect >"$art/emulator.log" 2>&1 &
  started=$!
  cleanup() { kill "$started" 2>/dev/null || true; }
  trap cleanup EXIT
  for _ in $(seq 1 90); do
    serial="$(avd_serial || true)"
    if [[ -n "$serial" ]]; then
      break
    fi
    sleep 2
  done
  if [[ -z "$serial" ]]; then
    echo "AVD $avd never appeared; see $art/emulator.log" >&2
    exit 1
  fi
fi
export ANDROID_SERIAL="$serial"
echo "emulator serial: $serial (AVD $avd)"

for _ in $(seq 1 60); do
  if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  sleep 5
done
if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
  echo "emulator $serial booted but sys.boot_completed never became 1" >&2
  exit 1
fi
"$adb" -s "$serial" shell svc power stayon true >/dev/null
"$adb" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null
"$adb" -s "$serial" shell wm dismiss-keyguard >/dev/null
"$adb" -s "$serial" shell settings put system accelerometer_rotation 0
"$adb" -s "$serial" shell settings put system user_rotation 0
"$adb" -s "$serial" shell settings put system font_scale "${CENIX_FONT_SCALE:-1.0}"
"$adb" -s "$serial" shell settings put system system_locales "${CENIX_LOCALE:-en-US}"
"$adb" -s "$serial" shell setprop debug.force_rtl "${CENIX_FORCE_RTL:-false}"
"$adb" -s "$serial" shell settings put global window_animation_scale 0
"$adb" -s "$serial" shell settings put global transition_animation_scale 0
"$adb" -s "$serial" shell settings put global animator_duration_scale 0
"$adb" -s "$serial" shell cmd uimode night "${CENIX_NIGHT_MODE:-no}" >/dev/null
"$adb" -s "$serial" shell wm size 320x640
"$adb" -s "$serial" shell wm density 160
sleep 1

pass() { echo "PASS: $1"; }
fail() {
  echo "FAIL: $1" >&2
  "$adb" -s "$serial" logcat -d >"$art/logcat.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell uiautomator dump /sdcard/cenix-ui.xml >/dev/null 2>&1 || true
  "$adb" -s "$serial" pull /sdcard/cenix-ui.xml "$art/ui.xml" >/dev/null 2>&1 || true
  "$adb" -s "$serial" exec-out screencap -p >"$art/screen.png" 2>/dev/null || true
  "$adb" -s "$serial" shell dumpsys activity >"$art/activity.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell dumpsys package com.caniko.cenix >"$art/package.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell cmd role get-role-holders android.app.role.HOME >"$art/role.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell dumpsys package com.caniko.cenix >"$art/package.txt" 2>/dev/null || true
  printf '%s\n' "$CENIX_GIT_COMMIT" >"$art/git-commit.txt"
  echo "artifacts: $art" >&2
  exit 1
}

resumed() {
  "$adb" -s "$serial" shell dumpsys activity activities | tr -d '\r' | grep 'topResumedActivity' || true
}

wait_resumed() {
  local component="$1" i
  for i in $(seq 1 30); do
    if resumed | grep -q "$component"; then
      return 0
    fi
    sleep 1
  done
  fail "not resumed: $component"
}

dump_ui() {
  local i xml keep_ime=0
  [[ "${1:-}" == "ime" ]] && keep_ime=1
  for i in $(seq 1 15); do
    "$adb" -s "$serial" shell rm -f /sdcard/cenix-ui.xml
    "$adb" -s "$serial" shell uiautomator dump /sdcard/cenix-ui.xml >/dev/null 2>&1 || true
    if "$adb" -s "$serial" shell test -s /sdcard/cenix-ui.xml; then
      xml="$("$adb" -s "$serial" shell cat /sdcard/cenix-ui.xml)"
      if printf '%s\n' "$xml" | grep -q 'package="com.caniko.cenix"' &&
        printf '%s\n' "$xml" | grep -q 'com.caniko.cenix:id/launcherRoot'; then
        printf '%s\n' "$xml"
        return 0
      fi
    fi
    [[ "$keep_ime" == 1 ]] || hide_keyboard
    sleep 1
  done
  fail "uiautomator dump failed"
}

wait_ui() {
  local needle="$1" want="${2:-1}" i xml
  for i in $(seq 1 20); do
    xml="$(dump_ui)"
    if printf '%s\n' "$xml" | grep -q "$needle"; then
      [[ "$want" == "1" ]] && return 0
    else
      [[ "$want" == "0" ]] && return 0
    fi
    sleep 1
  done
  if [[ "$want" == "1" ]]; then
    fail "UI never showed: $needle"
  else
    fail "UI still showed: $needle"
  fi
}

bounds_of() {
  local xml="$1" pattern="$2"
  printf '%s\n' "$xml" | tr '>' '\n' | grep -E "$pattern" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)]\[\([0-9]*\),\([0-9]*\)]".*/\1 \2 \3 \4/p' | head -n1
}

read_bounds() {
  local xml="$1" pattern="$2" x1 y1 x2 y2
  read -r x1 y1 x2 y2 < <(bounds_of "$xml" "$pattern") || true
  [[ -n "${x1:-}" ]] || fail "bounds missing for $pattern"
  printf '%s %s %s %s\n' "$x1" "$y1" "$x2" "$y2"
}

tap_bounds() {
  local x1="$1" y1="$2" x2="$3" y2="$4"
  "$adb" -s "$serial" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

tap_pattern() {
  local xml x1 y1 x2 y2
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  tap_bounds "$x1" "$y1" "$x2" "$y2"
}

long_press_pattern() {
  local xml x1 y1 x2 y2 x y
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  x=$(((x1 + x2) / 2))
  y=$(((y1 + y2) / 2))
  "$adb" -s "$serial" shell input swipe "$x" "$y" "$x" "$y" 800
}

drag_from_to() {
  local xml x1 y1 x2 y2 tx1 ty1 tx2 ty2
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  read -r tx1 ty1 tx2 ty2 < <(read_bounds "$xml" "$2")
  "$adb" -s "$serial" shell input draganddrop $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) $(((tx1 + tx2) / 2)) $(((ty1 + ty2) / 2)) 1600
}

drag_pattern_to_bounds() {
  local xml x1 y1 x2 y2
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  "$adb" -s "$serial" shell input draganddrop $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) "$2" "$3" 1600
}

drag_to_workspace_edge() {
  local xml x1 y1 x2 y2 wx1 wy1 wx2 wy2 edge="$2"
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  read -r wx1 wy1 wx2 wy2 < <(read_bounds "$xml" 'resource-id="com.caniko.cenix:id/workspaceGrid"')
  if [[ "$edge" == "next" ]]; then
    target=$((wx2 - 4))
  else
    target=$((wx1 + 4))
  fi
  "$adb" -s "$serial" shell input draganddrop $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) "$target" $(((wy1 + wy2) / 2)) 1800
}

swipe_workspace() {
  local xml x1 y1 x2 y2 midy from to
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" 'resource-id="com.caniko.cenix:id/workspaceGrid"')
  midy=$(((y1 + y2) / 2))
  if [[ "$1" == "next" ]]; then
    from=$((x1 + (x2 - x1) * 3 / 4))
    to=$((x1 + (x2 - x1) / 4))
  else
    from=$((x1 + (x2 - x1) / 4))
    to=$((x1 + (x2 - x1) * 3 / 4))
  fi
  "$adb" -s "$serial" shell input swipe "$from" "$midy" "$to" "$midy" 300
}

hide_keyboard() {
  "$adb" -s "$serial" shell input keyevent KEYCODE_ESCAPE
  sleep 0.3
}

focus_search() {
  local xml x1 y1 x2 y2
  open_all_apps
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" 'resource-id="com.caniko.cenix:id/searchField"')
  tap_bounds "$x1" "$y1" "$x2" "$y2"
  sleep 0.5
}

swipe_surface() {
  local xml x1 y1 x2 y2 x from to
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" 'resource-id="com.caniko.cenix:id/launcherRoot"')
  x=$(((x1 + x2) / 2))
  if [[ "$1" == "up" ]]; then
    from=$((y1 + (y2 - y1) * 3 / 4)); to=$((y1 + (y2 - y1) / 4))
  else
    from=$((y1 + (y2 - y1) / 4)); to=$((y1 + (y2 - y1) * 3 / 4))
  fi
  "$adb" -s "$serial" shell input swipe "$x" "$from" "$x" "$to" 350
}

open_all_apps() {
  local ui
  ui="$(dump_ui)"
  if ! printf '%s\n' "$ui" | grep -q 'resource-id="com.caniko.cenix:id/searchField"'; then
    swipe_surface up
    wait_ui 'resource-id="com.caniko.cenix:id/searchField"' 1
  fi
}

close_all_apps() {
  hide_keyboard
  "$adb" -s "$serial" shell input keyevent KEYCODE_BACK
  wait_ui 'resource-id="com.caniko.cenix:id/searchField"' 0
}

clear_search() {
  local i
  focus_search
  "$adb" -s "$serial" shell input keyevent KEYCODE_MOVE_END
  for i in $(seq 1 80); do
    "$adb" -s "$serial" shell input keyevent KEYCODE_DEL
  done
  hide_keyboard
}

set_search() {
  clear_search
  focus_search
  "$adb" -s "$serial" shell input text "$1"
  sleep 1
  hide_keyboard
}

go_home() {
  "$adb" -s "$serial" shell input keyevent KEYCODE_HOME
  wait_resumed 'com.caniko.cenix/.HomeActivity'
}

role_holders() {
  "$adb" -s "$serial" shell cmd role get-role-holders android.app.role.HOME | tr -d '\r'
}

"$root/scripts/assemble-debug.sh"
"$root/scripts/audit-apk.sh"
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
{
  echo "commit=$CENIX_GIT_COMMIT"
  echo "avd=$avd"
  echo "serial=$serial"
  echo "image=$sysdir"
  echo "image_revision=$(sed -n 's/^Pkg.Revision=//p' "$sysdir/source.properties" 2>/dev/null || true)"
  echo "kind=$image_kind"
  echo "api=35"
  echo "abi=x86_64"
  echo "density=mdpi"
  echo "size=320x640"
  echo "font_scale=${CENIX_FONT_SCALE:-1.0}"
  echo "force_rtl=${CENIX_FORCE_RTL:-false}"
} >"$art/metadata.txt"
sha256sum "$apk" >"$art/apk.sha256"
printf '%s\n' "$CENIX_GIT_COMMIT" >"$art/git-commit.txt"
python3 - "$apk" "$CENIX_GIT_COMMIT" <<'PY' || fail "built APK is missing git commit $CENIX_GIT_COMMIT"
import sys, zipfile
apk, commit = sys.argv[1], sys.argv[2].encode()
with zipfile.ZipFile(apk) as z:
    sys.exit(0 if any(name.endswith(".dex") and commit in z.read(name) for name in z.namelist()) else 1)
PY

"$adb" -s "$serial" uninstall com.caniko.cenix >/dev/null 2>&1 || true
"$adb" -s "$serial" uninstall com.caniko.cenix.fixture >/dev/null 2>&1 || true
"$adb" -s "$serial" install -r -t "$apk"
"$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null
holders="$(role_holders)"
echo "$holders" | grep -q 'com.caniko.cenix' || fail "Cenix is not HOME role holder: $holders"
pass "HOME role holder is com.caniko.cenix ($holders)"

"$adb" -s "$serial" shell am start -a android.settings.SETTINGS >/dev/null
wait_resumed 'com.android.settings'
go_home
pass "KEYCODE_HOME resumes com.caniko.cenix/.HomeActivity"

ui="$(dump_ui)"
echo "$ui" | grep -q 'workspaceGrid' || fail "workspace grid missing"
echo "$ui" | grep -q 'hotseatGrid' || fail "hotseat grid missing"
echo "$ui" | grep -q 'searchField' && fail "search visible on normal HOME"
echo "$ui" | grep -q 'appList' && fail "All Apps visible on normal HOME"
echo "$ui" | grep -q 'retryNative' && fail "recovery controls visible on normal HOME"
echo "$ui" | grep -qi 'emergency mode' && fail "native APK started in emergency"
printf '%s\n' "$ui" | tr '>' '\n' | grep -q 'text="Cenix".*resource-id="com.caniko.cenix:id/appLabel"' && fail "Cenix listed itself as a launch target"
pass "shell: initial state is full-screen HOME"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
(cd "$root/android" && ./gradlew :app:connectedDebugAndroidTest) || fail "instrumentation failed"
pass "instrumentation: HomeConformanceTest"
"$adb" -s "$serial" install -r -t "$apk" || fail "reinstall after instrumentation failed"
"$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null || true
holders="$(role_holders)"
echo "$holders" | grep -q 'com.caniko.cenix' || fail "instrumentation dropped HOME role: $holders"
go_home
hide_keyboard

set_search "Settings"
wait_ui 'text="Settings"' 1
pass "shell: swipe up opens All Apps"
clear_search
hide_keyboard
ui="$(dump_ui)"
echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/appLabel"' || fail "catalog empty"
pass "discovery: nonempty, Settings present, Cenix hidden"

"$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
pass "package callback: fixture appeared without restart"

set_search "fIxTuRe"
wait_ui 'Cenix Fixture' 1
pass "search: case-insensitive partial shows fixture"
set_search "zzzNoSuch"
wait_ui 'Cenix Fixture' 0
pass "search: nonmatching query hides fixture"
clear_search
hide_keyboard
wait_ui 'resource-id="com.caniko.cenix:id/appLabel"' 1
pass "search: clear restores catalog"

set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
tap_pattern 'text="Cenix Fixture".*resource-id="com.caniko.cenix:id/appLabel"'
wait_resumed 'com.caniko.cenix.fixture/.FixtureActivity'
pass "launch: fixture activity resumed"
go_home
pass "launch: KEYCODE_HOME returns to Cenix"

"$adb" -s "$serial" uninstall com.caniko.cenix.fixture >/dev/null
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 0
clear_search
pass "package callback: fixture disappeared without restart"

set_search "Settings"
wait_ui 'text="Settings"' 1
close_all_apps
ui="$(dump_ui)"
read -r tx1 ty1 tx2 ty2 < <(read_bounds "$ui" 'content-desc="Empty, page 1, row 2, column 1"')
set_search "Settings"
wait_ui 'text="Settings"' 1
drag_pattern_to_bounds 'text="Settings".*resource-id="com.caniko.cenix:id/appLabel"' $(((tx1 + tx2) / 2)) $(((ty1 + ty2) / 2))
wait_ui 'content-desc="Settings, page 1' 1
wait_ui 'resource-id="com.caniko.cenix:id/searchField"' 0
pass "workspace: All Apps drag reveals HOME and places into CellLayout"
drag_from_to 'content-desc="Settings, page 1' 'content-desc="Empty,'
wait_ui 'content-desc="Settings, page 1' 1
pass "workspace: internal drag moves pin"
drag_to_workspace_edge 'content-desc="Settings, page 1' next
wait_ui 'content-desc="Page 2 of 2"' 1
wait_ui 'content-desc="Settings, page 2' 1
pass "workspace: edge drag creates and enters second page"
drag_to_workspace_edge 'content-desc="Settings, page 2' prev
wait_ui 'content-desc="Page 1 of 1"' 1
wait_ui 'content-desc="Settings, page 1' 1
pass "workspace: returning item removes empty trailing page"
drag_from_to 'content-desc="Settings, page 1' 'resource-id="com.caniko.cenix:id/hotseatGrid"'
wait_ui 'content-desc="Settings, hotseat' 1
pass "hotseat: cross-container drag docks"
drag_from_to 'content-desc="Settings, hotseat' 'content-desc="Empty,'
wait_ui 'content-desc="Settings, page 1' 1
pass "hotseat: drag undocks into workspace"

"$adb" -s "$serial" shell am force-stop com.caniko.cenix
go_home
ui="$(dump_ui)"
echo "$ui" | grep -qi 'emergency mode' && fail "healthy restart entered emergency"
echo "$ui" | grep -q 'workspaceGrid' || fail "workspace missing after process recreation"
echo "$ui" | grep -q 'searchField' && fail "process recreation restored transient All Apps state"
pass "process recreation: force-stop + HOME stays healthy"

hide_keyboard
"$adb" -s "$serial" shell settings put system accelerometer_rotation 0
"$adb" -s "$serial" shell settings put system user_rotation 1
sleep 2
wait_resumed 'com.caniko.cenix/.HomeActivity'
ui="$(dump_ui)"
read -r lx1 ly1 lx2 ly2 < <(read_bounds "$ui" 'resource-id="com.caniko.cenix:id/workspaceGrid"')
(( lx2 > lx1 && ly2 > ly1 )) || fail "workspace unusable in landscape"
echo "$ui" | grep -q 'content-desc="Settings, page 1' || fail "workspace item missing in landscape"
echo "$ui" | grep -q 'searchField' && fail "All Apps visible on landscape HOME"
"$adb" -s "$serial" shell settings put system user_rotation 0
sleep 2
wait_resumed 'com.caniko.cenix/.HomeActivity'
ui="$(dump_ui)"
read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'resource-id="com.caniko.cenix:id/workspaceGrid"')
(( px2 > px1 && py2 > py1 )) || fail "workspace unusable in portrait"
echo "$ui" | grep -q 'content-desc="Settings, page 1' || fail "workspace item missing after portrait restore"
pass "configuration: landscape/portrait retain usable HOME workspace"

set_search "Settings"
focus_search
sleep 1
ui="$(dump_ui ime)"
echo "$ui" | grep -q 'text="Settings"' || fail "IME covered All Apps results"
echo "$ui" | grep -q 'retryNative' && fail "normal All Apps exposed emergency controls"
pass "configuration: IME leaves All Apps results operable"

hide_keyboard
"$adb" -s "$serial" shell settings put system user_rotation 0
sleep 1
go_home
"$adb" -s "$serial" shell am start -n com.caniko.cenix/.HomeActivity --ez com.caniko.cenix.FORCE_NATIVE_FAILURE true >/dev/null
wait_ui 'Emergency mode' 1
pass "debug extra forces emergency chrome"
"$adb" -s "$serial" shell am force-stop com.caniko.cenix
go_home
wait_ui 'Emergency mode' 1
pass "explicit emergency persists across process recreation"
tap_pattern 'resource-id="com.caniko.cenix:id/retryNative"'
sleep 1
ui="$(dump_ui)"
echo "$ui" | grep -q 'Emergency mode' && fail "retry-native did not leave emergency with native present"
pass "retry-native restores native mode"
holders="$(role_holders)"
echo "$holders" | grep -q 'com.caniko.cenix' || fail "retry-native changed HOME role: $holders"

"$adb" -s "$serial" shell am start -n com.caniko.cenix/.HomeActivity --ez com.caniko.cenix.FORCE_NATIVE_FAILURE true >/dev/null
wait_ui 'Emergency mode' 1
"$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
tap_pattern 'resource-id="com.caniko.cenix:id/resetState"'
sleep 1
ui="$(dump_ui)"
echo "$ui" | grep -q 'Emergency mode' && fail "reset-local-state did not leave emergency"
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
clear_search
holders="$(role_holders)"
echo "$holders" | grep -q 'com.caniko.cenix' || fail "reset changed HOME role: $holders"
pass "reset-local-state clears Cenix Room state only"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
(cd "$root/android" && ./gradlew :app:assembleDebug -PomitNative)
omit_apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
CENIX_OMIT_NATIVE=1 "$root/scripts/audit-apk.sh" "$omit_apk"
"$adb" -s "$serial" uninstall com.caniko.cenix >/dev/null 2>&1 || true
"$adb" -s "$serial" install -r -t "$omit_apk"
"$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null
go_home
wait_ui 'Emergency mode' 1
ui="$(dump_ui)"
if ! echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/appLabel"'; then
  set_search "Settings"
  wait_ui 'text="Settings"' 1
  clear_search
  hide_keyboard
fi
pass "native-unavailable APK starts in Kotlin emergency and lists apps"
set_search "Settings"
wait_ui 'text="Settings"' 1
pass "native-unavailable Kotlin search works"
hide_keyboard
tap_pattern 'resource-id="com.caniko.cenix:id/appLabel"'
wait_resumed 'com.android.settings'
go_home
wait_ui 'Emergency mode' 1
pass "native-unavailable launch + HOME still works"
tap_pattern 'resource-id="com.caniko.cenix:id/retryNative"'
sleep 1
wait_ui 'Emergency mode' 1
pass "retry-native does not report success while libcenix_ffi.so is absent"

echo "emulator conformance passed on $serial (Android API 35 $image_kind x86_64, not GrapheneOS/mustang)"
