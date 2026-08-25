#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
suite="full"
if [[ "${1:-}" == "--suite" ]]; then
  suite="${2:-}"
  shift 2
fi
[[ $# == 0 ]] || { echo "usage: $0 [--suite core|workspace|folders|shortcuts|widgets|full]" >&2; exit 2; }
case "$suite" in core|workspace|folders|shortcuts|widgets|full) ;; *) echo "unknown suite: $suite" >&2; exit 2 ;; esac
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
adb="${ADB:-adb}"
run_id="${CENIX_RUN_ID:-$$-$(date +%s)}"
art="${CENIX_ARTIFACTS:-$(mktemp -d /tmp/cenix-conformance.XXXXXX)}"
workspace_rtl=0
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
echo "suite: $suite"

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
rtl_boot=()
if [[ "${CENIX_FORCE_RTL:-false}" == "true" ]]; then
  rtl_boot=(-prop debug.force_rtl=true)
fi
if [[ -z "$serial" ]]; then
  "$emulator_bin" -avd "$avd" -port "$emu_port" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect "${rtl_boot[@]}" >"$art/emulator.log" 2>&1 &
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
      if grep -q 'package="com.caniko.cenix"' <<<"$xml" &&
        grep -Eq 'com.caniko.cenix:id/(launcherRoot|pin_confirmation|widget_picker)' <<<"$xml"; then
        printf '%s\n' "$xml"
        return 0
      fi
    fi
    [[ "$keep_ime" == 1 ]] || hide_keyboard
    sleep 1
  done
  fail "uiautomator dump failed"
}

dump_any_ui() {
  local xml
  "$adb" -s "$serial" shell uiautomator dump /sdcard/cenix-any-ui.xml >/dev/null
  xml="$("$adb" -s "$serial" shell cat /sdcard/cenix-any-ui.xml)"
  printf '%s\n' "$xml"
}

wait_ui() {
  local needle="$1" want="${2:-1}" i xml
  for i in $(seq 1 20); do
    xml="$(dump_ui)"
    if grep -q "$needle" <<<"$xml"; then
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

tap_any_pattern() {
  local xml x1 y1 x2 y2
  xml="$(dump_any_ui)"
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

long_press_pattern_top() {
  local xml x1 y1 x2 y2 x y
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  x=$(((x1 + x2) / 2))
  y=$((y1 + 24))
  "$adb" -s "$serial" shell input swipe "$x" "$y" "$x" "$y" 800
}

drag_coordinates() {
  "$adb" -s "$serial" shell input motionevent DOWN "$1" "$2"
  sleep 0.8
  "$adb" -s "$serial" shell input motionevent MOVE "$3" "$4"
  sleep 0.1
  "$adb" -s "$serial" shell input motionevent UP "$3" "$4"
}

drag_from_to() {
  local xml x1 y1 x2 y2 tx1 ty1 tx2 ty2
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  read -r tx1 ty1 tx2 ty2 < <(read_bounds "$xml" "$2")
  if [[ "$1" == *appLabel* ]]; then
    drag_coordinates $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) $(((tx1 + tx2) / 2)) $(((ty1 + ty2) / 2))
  else
    "$adb" -s "$serial" shell input draganddrop $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) $(((tx1 + tx2) / 2)) $(((ty1 + ty2) / 2)) 1600
  fi
}

drag_pattern_to_bounds() {
  local xml x1 y1 x2 y2
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  drag_coordinates $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) "$2" "$3"
}

drag_hold_open() {
  local xml x1 y1 x2 y2 hx1 hy1 hx2 hy2 tx1 ty1 tx2 ty2
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  read -r hx1 hy1 hx2 hy2 < <(read_bounds "$xml" "$2")
  "$adb" -s "$serial" shell input motionevent DOWN $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
  sleep 0.8
  "$adb" -s "$serial" shell input motionevent MOVE $(((hx1 + hx2) / 2)) $(((hy1 + hy2) / 2))
  sleep 1
  wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 1
  xml="$(dump_ui)"
  read -r tx1 ty1 tx2 ty2 < <(read_bounds "$xml" "$3")
  "$adb" -s "$serial" shell input motionevent MOVE $(((tx1 + tx2) / 2)) $(((ty1 + ty2) / 2))
  "$adb" -s "$serial" shell input motionevent UP $(((tx1 + tx2) / 2)) $(((ty1 + ty2) / 2))
}

drag_to_workspace_edge() {
  local xml x1 y1 x2 y2 wx1 wy1 wx2 wy2 edge="$2"
  xml="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$xml" "$1")
  read -r wx1 wy1 wx2 wy2 < <(read_bounds "$xml" 'resource-id="com.caniko.cenix:id/workspaceGrid"')
  if [[ "$edge" == "next" && "$workspace_rtl" == "0" ]] ||
    [[ "$edge" == "prev" && "$workspace_rtl" == "1" ]]; then
    target=$((wx2 - 24))
  else
    target=$((wx1 + 24))
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

filter_widgets() {
  local ui x1 y1 x2 y2
  wait_ui 'resource-id="com.caniko.cenix:id/widget_search"' 1
  ui="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$ui" 'resource-id="com.caniko.cenix:id/widget_search"')
  tap_bounds "$x1" "$y1" "$x2" "$y2"
  "$adb" -s "$serial" shell input text Fixture
  sleep 1
  hide_keyboard
}

accept_widget_bind() {
  local ui i x1 y1 x2 y2
  for i in $(seq 1 20); do
    ui="$(dump_any_ui)"
    if grep -q 'package="com.android.settings"' <<<"$ui"; then
      read -r x1 y1 x2 y2 < <(read_bounds "$ui" 'resource-id="android:id/button1"')
      tap_bounds "$x1" "$y1" "$x2" "$y2"
      return 0
    fi
    if grep -q 'resource-id="com.caniko.cenix:id/launcherRoot"' <<<"$ui"; then
      return 0
    fi
    sleep 0.5
  done
  fail "widget bind permission surface did not settle"
}

go_home() {
  "$adb" -s "$serial" shell input keyevent KEYCODE_HOME
  wait_resumed 'com.caniko.cenix/.HomeActivity'
}

open_fixture_control() {
  "$adb" -s "$serial" shell am force-stop com.caniko.cenix.fixture
  "$adb" -s "$serial" shell am start -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
  wait_resumed 'com.caniko.cenix.fixture/.FixtureActivity'
}

role_holders() {
  "$adb" -s "$serial" shell cmd role get-role-holders android.app.role.HOME | tr -d '\r'
}

apply_app_rtl() {
  if [[ "${CENIX_FORCE_RTL:-false}" == "true" ]]; then
    "$adb" -s "$serial" shell cmd locale set-app-locales com.caniko.cenix --user 0 --locales ar >/dev/null
  fi
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
  echo "suite=$suite"
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
apply_app_rtl
"$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null
holders="$(role_holders)"
echo "$holders" | grep -q 'com.caniko.cenix' || fail "Cenix is not HOME role holder: $holders"
pass "HOME role holder is com.caniko.cenix ($holders)"

"$adb" -s "$serial" shell am start -a android.settings.SETTINGS >/dev/null
wait_resumed 'com.android.settings'
go_home
pass "KEYCODE_HOME resumes com.caniko.cenix/.HomeActivity"

ui="$(dump_ui)"
read -r rtl1x _ _ _ < <(read_bounds "$ui" 'content-desc="Empty, page 1, row 1, column 1"')
read -r rtl4x _ _ _ < <(read_bounds "$ui" 'content-desc="Empty, page 1, row 1, column 4"')
(( rtl1x > rtl4x )) && workspace_rtl=1
if [[ "${CENIX_FORCE_RTL:-false}" == "true" && "$workspace_rtl" != "1" ]]; then
  fail "forced RTL did not mirror workspace columns"
fi
echo "$ui" | grep -q 'workspaceGrid' || fail "workspace grid missing"
echo "$ui" | grep -q 'hotseatGrid' || fail "hotseat grid missing"
echo "$ui" | grep -q 'searchField' && fail "search visible on normal HOME"
echo "$ui" | grep -q 'appList' && fail "All Apps visible on normal HOME"
echo "$ui" | grep -q 'retryNative' && fail "recovery controls visible on normal HOME"
echo "$ui" | grep -qi 'emergency mode' && fail "native APK started in emergency"
printf '%s\n' "$ui" | tr '>' '\n' | grep -q 'text="Cenix".*resource-id="com.caniko.cenix:id/appLabel"' && fail "Cenix listed itself as a launch target"
pass "shell: initial state is full-screen HOME"

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
"$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
"$adb" -s "$serial" shell am start -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
"$adb" -s "$serial" shell input keyevent KEYCODE_HOME
(cd "$root/android" && ./gradlew :app:connectedDebugAndroidTest) || fail "instrumentation failed"
pass "instrumentation: HomeConformanceTest"
"$adb" -s "$serial" uninstall com.caniko.cenix.fixture >/dev/null
"$adb" -s "$serial" install -r -t "$apk" || fail "reinstall after instrumentation failed"
apply_app_rtl
"$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null || true
holders="$(role_holders)"
echo "$holders" | grep -q 'com.caniko.cenix' || fail "instrumentation dropped HOME role: $holders"
go_home
hide_keyboard

open_all_apps
wait_ui 'resource-id="com.caniko.cenix:id/appLabel"' 1
pass "shell: swipe up opens All Apps"
clear_search
hide_keyboard
ui="$(dump_ui)"
echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/appLabel"' || fail "catalog empty"
pass "discovery: nonempty catalog, Cenix hidden"

"$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
"$adb" -s "$serial" install -r -t "$root/android/fixture-secondary/build/outputs/apk/debug/fixture-secondary-debug.apk"
"$adb" -s "$serial" shell am start -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
go_home
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

if [[ "$suite" == "workspace" || "$suite" == "folders" || "$suite" == "full" ]]; then
"$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
"$adb" -s "$serial" shell am start -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
go_home
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
close_all_apps
ui="$(dump_ui)"
read -r tx1 ty1 tx2 ty2 < <(read_bounds "$ui" 'content-desc="Empty, page 1, row 2, column 1"')
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
drag_pattern_to_bounds 'text="Cenix Fixture".*resource-id="com.caniko.cenix:id/appLabel"' $(((tx1 + tx2) / 2)) $(((ty1 + ty2) / 2))
wait_ui 'content-desc="Cenix Fixture, page 1' 1
wait_ui 'resource-id="com.caniko.cenix:id/searchField"' 0
pass "workspace: All Apps drag reveals HOME and places into CellLayout"
drag_from_to 'content-desc="Cenix Fixture, page 1' 'content-desc="Empty,'
wait_ui 'content-desc="Cenix Fixture, page 1' 1
pass "workspace: internal drag moves pin"
drag_to_workspace_edge 'content-desc="Cenix Fixture, page 1' next
wait_ui 'content-desc="Page 2 of 2"' 1
wait_ui 'content-desc="Cenix Fixture, page 2' 1
pass "workspace: edge drag creates and enters second page"
drag_to_workspace_edge 'content-desc="Cenix Fixture, page 2' prev
wait_ui 'content-desc="Page 1 of 1"' 1
wait_ui 'content-desc="Cenix Fixture, page 1' 1
pass "workspace: returning item removes empty trailing page"
drag_from_to 'content-desc="Cenix Fixture, page 1' 'resource-id="com.caniko.cenix:id/hotseatGrid"'
wait_ui 'content-desc="Cenix Fixture, hotseat' 1
pass "hotseat: cross-container drag docks"
drag_from_to 'content-desc="Cenix Fixture, hotseat' 'content-desc="Empty,'
wait_ui 'content-desc="Cenix Fixture, page 1' 1
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
echo "$ui" | grep -q 'content-desc="Cenix Fixture, page 1' || fail "workspace item missing in landscape"
echo "$ui" | grep -q 'searchField' && fail "All Apps visible on landscape HOME"
"$adb" -s "$serial" shell settings put system user_rotation 0
sleep 2
wait_resumed 'com.caniko.cenix/.HomeActivity'
ui="$(dump_ui)"
read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'resource-id="com.caniko.cenix:id/workspaceGrid"')
(( px2 > px1 && py2 > py1 )) || fail "workspace unusable in portrait"
echo "$ui" | grep -q 'content-desc="Cenix Fixture, page 1' || fail "workspace item missing after portrait restore"
pass "configuration: landscape/portrait retain usable HOME workspace"

set_search "Fixture"
focus_search
sleep 1
ui="$(dump_ui ime)"
echo "$ui" | grep -q 'text="Cenix Fixture"' || fail "IME covered All Apps results"
echo "$ui" | grep -q 'retryNative' && fail "normal All Apps exposed emergency controls"
pass "configuration: IME leaves All Apps results operable"

hide_keyboard
"$adb" -s "$serial" shell settings put system user_rotation 0
sleep 1
go_home
fi

if [[ "$suite" == "folders" || "$suite" == "full" ]]; then
ui="$(dump_ui)"
read -r fx1 fy1 fx2 fy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
set_search "Two"
wait_ui 'text="Cenix Fixture Two"' 1
drag_pattern_to_bounds 'text="Cenix Fixture Two".*resource-id="com.caniko.cenix:id/appLabel"' $(((fx1 + fx2) / 2)) $(((fy1 + fy2) / 2))
wait_ui 'content-desc="Cenix Fixture Two, page 1' 1
drag_from_to 'content-desc="Cenix Fixture Two, page 1' 'content-desc="Cenix Fixture, page 1'
wait_ui 'folder, .* applications' 1
pass "folders: central occupied-cell drop creates two-member folder"
tap_pattern 'folder, .* applications'
wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 1
wait_ui 'content-desc="Cenix Fixture Two, rank 2"' 1
pass "folders: popup exposes ranked members"
tap_pattern 'resource-id="com.caniko.cenix:id/folder_title"'
"$adb" -s "$serial" shell input text "Utilities"
"$adb" -s "$serial" shell input keyevent KEYCODE_ENTER
wait_ui 'text="Utilities".*resource-id="com.caniko.cenix:id/folder_title"' 1
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 1
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 0
wait_ui 'content-desc="Utilities, folder, .* applications' 1
"$adb" -s "$serial" shell am force-stop com.caniko.cenix
go_home
wait_ui 'content-desc="Utilities, folder, .* applications' 1
pass "folders: title and membership survive process recreation"

set_search "Three"
wait_ui 'text="Cenix Fixture Three"' 1
drag_from_to 'text="Cenix Fixture Three".*resource-id="com.caniko.cenix:id/appLabel"' 'content-desc="Utilities, folder,'
wait_ui 'content-desc="Utilities, folder, .* applications' 1
tap_pattern 'content-desc="Utilities, folder, .* applications'
wait_ui 'content-desc="Cenix Fixture Three, rank 3"' 1
pass "folders: closed-folder append adds the final rank"
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 0

ui="$(dump_ui)"
read -r fx1 fy1 fx2 fy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
set_search "Four"
wait_ui 'text="Cenix Fixture Four"' 1
drag_pattern_to_bounds 'text="Cenix Fixture Four".*resource-id="com.caniko.cenix:id/appLabel"' $(((fx1 + fx2) / 2)) $(((fy1 + fy2) / 2))
wait_ui 'content-desc="Cenix Fixture Four, page 1' 1
drag_hold_open 'content-desc="Cenix Fixture Four, page 1' 'content-desc="Utilities, folder,' 'content-desc="Cenix Fixture, rank 1"'
wait_ui 'content-desc="Cenix Fixture Four, rank 1"' 1
pass "folders: 800ms spring-open retains payload and adds at semantic rank"
drag_from_to 'content-desc="Cenix Fixture Three, rank 4"' 'content-desc="Cenix Fixture Four, rank 1"'
wait_ui 'content-desc="Cenix Fixture Three, rank 1"' 1
pass "folders: open member reorder normalizes ranks"
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK

long_press_pattern 'content-desc="Utilities, folder,'
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
wait_ui 'content-desc="Utilities, folder,' 1
pass "folders: cancelled folder drag preserves committed state"
drag_from_to 'content-desc="Utilities, folder,' 'content-desc="Empty, page 1'
wait_ui 'content-desc="Utilities, folder,' 1
drag_to_workspace_edge 'content-desc="Utilities, folder,' next
wait_ui 'content-desc="Utilities, folder, .*page 2' 1
drag_from_to 'content-desc="Utilities, folder, .*page 2' 'resource-id="com.caniko.cenix:id/hotseatGrid"'
wait_ui 'content-desc="Utilities, folder, .*hotseat' 1
drag_from_to 'content-desc="Utilities, folder, .*hotseat' 'content-desc="Empty, page 1'
wait_ui 'content-desc="Utilities, folder, .*page 1' 1
tap_pattern 'content-desc="Utilities, folder, .*page 1'
wait_ui 'content-desc="Cenix Fixture Three, rank 1"' 1
pass "folders: cell, page, hotseat, and workspace moves preserve member order"
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK

set_search "Auxiliary"
wait_ui 'text="Cenix Auxiliary"' 1
drag_from_to 'text="Cenix Auxiliary".*resource-id="com.caniko.cenix:id/appLabel"' 'content-desc="Utilities, folder,'
tap_pattern 'content-desc="Utilities, folder,'
wait_ui 'content-desc="Cenix Auxiliary, rank 5"' 1
"$adb" -s "$serial" uninstall com.caniko.cenix.fixture.secondary >/dev/null
wait_ui 'content-desc="Cenix Auxiliary' 0
wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 1
pass "folders: package removal refreshes an open popup without changing title"
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK

"$adb" -s "$serial" install -r -t "$root/android/fixture-secondary/build/outputs/apk/debug/fixture-secondary-debug.apk"
set_search "Auxiliary"
drag_from_to 'text="Cenix Auxiliary".*resource-id="com.caniko.cenix:id/appLabel"' 'content-desc="Utilities, folder,'
tap_pattern 'content-desc="Utilities, folder,'
ui="$(dump_ui)"
read -r fx1 fy1 fx2 fy2 < <(read_bounds "$ui" 'content-desc="Cenix Auxiliary, rank 5"')
"$adb" -s "$serial" shell input motionevent DOWN $(((fx1 + fx2) / 2)) $(((fy1 + fy2) / 2))
sleep 0.8
"$adb" -s "$serial" uninstall com.caniko.cenix.fixture.secondary >/dev/null
"$adb" -s "$serial" shell input motionevent UP $(((fx1 + fx2) / 2)) $(((fy1 + fy2) / 2))
wait_ui 'content-desc="Cenix Auxiliary' 0
pass "folders: package removal cancels an active member drag safely"
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK

"$adb" -s "$serial" install -r -t "$root/android/fixture-secondary/build/outputs/apk/debug/fixture-secondary-debug.apk"
set_search "Auxiliary"
drag_from_to 'text="Cenix Auxiliary".*resource-id="com.caniko.cenix:id/appLabel"' 'content-desc="Utilities, folder,'
"$adb" -s "$serial" uninstall com.caniko.cenix.fixture.secondary >/dev/null
wait_ui 'content-desc="Utilities, folder,' 1
pass "folders: package removal reconciles a closed folder"

"$adb" -s "$serial" install -r -t "$root/android/fixture-secondary/build/outputs/apk/debug/fixture-secondary-debug.apk"
set_search "Auxiliary"
drag_from_to 'text="Cenix Auxiliary".*resource-id="com.caniko.cenix:id/appLabel"' 'content-desc="Utilities, folder,'
tap_pattern 'content-desc="Utilities, folder,'
"$adb" -s "$serial" shell settings put system user_rotation 1
sleep 2
wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 0
"$adb" -s "$serial" shell settings put system user_rotation 0
sleep 2
wait_ui 'content-desc="Utilities, folder,' 1
tap_pattern 'content-desc="Utilities, folder,'
pass "folders: rotation closes transient popup and preserves durable membership"
for member in "Cenix Fixture Two" "Cenix Fixture Four" "Cenix Fixture Three"; do
  ui="$(dump_ui)"
  read -r fx1 fy1 fx2 fy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
  drag_pattern_to_bounds "content-desc=\"$member, rank" $(((fx1 + fx2) / 2)) $(((fy1 + fy2) / 2))
done
wait_ui 'content-desc="Cenix Auxiliary, rank 2"' 1
"$adb" -s "$serial" uninstall com.caniko.cenix.fixture.secondary >/dev/null
wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 0
wait_ui 'content-desc="Cenix Fixture, page 1' 1
wait_ui 'content-desc="Utilities, folder,' 0
pass "folders: package removal transactionally dissolves a two-member folder"
fi

if [[ "$suite" == "shortcuts" ]]; then
  "$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
  "$adb" -s "$serial" install -r -t "$root/android/fixture-secondary/build/outputs/apk/debug/fixture-secondary-debug.apk"
  "$adb" -s "$serial" shell am start -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
  go_home
  for label in "Cenix Fixture" "Cenix Fixture Two"; do
    ui="$(dump_ui)"
    read -r fx1 fy1 fx2 fy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
    set_search "${label##* }"
    wait_ui "text=\"$label\"" 1
    drag_pattern_to_bounds "text=\"$label\".*resource-id=\"com.caniko.cenix:id/appLabel\"" $(((fx1 + fx2) / 2)) $(((fy1 + fy2) / 2))
    wait_ui "content-desc=\"$label, page 1" 1
  done
fi

if [[ "$suite" == "shortcuts" || "$suite" == "full" ]]; then
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
long_press_pattern 'text="Cenix Fixture".*resource-id="com.caniko.cenix:id/appLabel"'
wait_ui 'resource-id="com.caniko.cenix:id/context_popup"' 1
wait_ui 'text="Manifest action"' 1
wait_ui 'text="Dynamic action"' 1
tap_pattern 'text="Manifest action"'
wait_resumed 'com.caniko.cenix.fixture/.ManifestShortcutActivity'
go_home
pass "shortcuts: All Apps context shows and launches manifest and dynamic shortcuts"

long_press_pattern 'content-desc="Cenix Fixture, page 1'
wait_ui 'resource-id="com.caniko.cenix:id/context_popup"' 1
tap_pattern 'resource-id="com.caniko.cenix:id/context_app_info"'
wait_resumed 'com.android.settings'
go_home
pass "context: workspace app info opens the profile-aware system surface"

long_press_pattern 'content-desc="Cenix Fixture, page 1'
wait_ui 'resource-id="com.caniko.cenix:id/context_popup"' 1
ui="$(dump_ui)"
read -r fx1 fy1 fx2 fy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
drag_pattern_to_bounds 'text="Manifest action"' $(((fx1 + fx2) / 2)) $(((fy1 + fy2) / 2))
wait_ui 'content-desc="Manifest action, page 1' 1
drag_from_to 'content-desc="Manifest action, page 1' 'resource-id="com.caniko.cenix:id/hotseatGrid"'
wait_ui 'content-desc="Manifest action, hotseat' 1
drag_from_to 'content-desc="Manifest action, hotseat' 'content-desc="Empty, page 1'
wait_ui 'content-desc="Manifest action, page 1' 1
pass "shortcuts: popup drag places, docks, and undocks a typed shortcut"

drag_from_to 'content-desc="Manifest action, page 1' 'content-desc="Cenix Fixture, page 1'
wait_ui 'folder, .* applications' 1
tap_pattern 'folder, .* applications'
wait_ui 'content-desc="Manifest action, rank 2"' 1
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
pass "shortcuts: application and shortcut coexist in a same-profile folder"

open_fixture_control
tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/pin_dynamic"'
wait_ui 'resource-id="com.caniko.cenix:id/pin_confirmation"' 1
tap_pattern 'resource-id="com.caniko.cenix:id/pin_cancel"'
go_home
wait_ui 'content-desc="Dynamic action, page' 0
pass "shortcuts: incoming pin cancellation writes nothing"

open_fixture_control
tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/pin_dynamic"'
wait_ui 'resource-id="com.caniko.cenix:id/pin_confirmation"' 1
tap_pattern 'resource-id="com.caniko.cenix:id/pin_confirm"'
go_home
wait_ui 'content-desc="Dynamic action, page 1' 1
pass "shortcuts: explicit incoming pin acceptance commits one durable item"

open_fixture_control
tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/update_dynamic"'
go_home
wait_ui 'content-desc="Updated dynamic action, page 1' 1
pass "shortcuts: dynamic label and rank refresh from LauncherApps"

long_press_pattern 'content-desc="Updated dynamic action, page 1'
wait_ui 'resource-id="com.caniko.cenix:id/context_popup"' 1
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
wait_ui 'resource-id="com.caniko.cenix:id/context_popup"' 0
pass "context: pinned shortcut popup closes with Back and is not restored"

open_fixture_control
tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/disable_dynamic"'
go_home
wait_ui 'content-desc="Updated dynamic action, page 1' 0
pass "shortcuts: disabled shortcut callback reconciles durable placement"

long_press_pattern 'content-desc="Cenix Fixture Two, page 1'
wait_ui 'resource-id="com.caniko.cenix:id/context_popup"' 1
tap_pattern 'resource-id="com.caniko.cenix:id/context_uninstall"'
sleep 1
resumed | grep -Eq 'com\.android\.permissioncontroller|com(\.google)?\.android\.packageinstaller' || fail "uninstall confirmation did not open"
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
go_home
wait_ui 'content-desc="Cenix Fixture Two, page 1' 1
pass "context: uninstall always uses Android confirmation and cancellation preserves package"

"$adb" -s "$serial" uninstall com.caniko.cenix.fixture >/dev/null
wait_ui 'content-desc="Manifest action' 0
pass "folders/shortcuts: package uninstall reconciles folder members and pinned shortcuts"
fi

if [[ "$suite" == "widgets" || "$suite" == "full" ]]; then
"$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
"$adb" -s "$serial" shell am start -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
go_home

ui="$(dump_ui)"
read -r wx1 wy1 wx2 wy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
"$adb" -s "$serial" shell input swipe $(((wx1 + wx2) / 2)) $(((wy1 + wy2) / 2)) $(((wx1 + wx2) / 2)) $(((wy1 + wy2) / 2)) 800
filter_widgets
wait_ui 'text="Fixture collection widget"' 1
tap_pattern 'text="Fixture collection widget"'
accept_widget_bind
wait_ui 'text="Widget update 0"' 1
wait_ui 'text="Row 1, update 0"' 1
pass "widgets: picker binds and renders a real collection-backed host view"

tap_pattern 'text="Open fixture"'
wait_resumed 'com.caniko.cenix.fixture/.FixtureActivity'
tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/update_widget"'
go_home
wait_ui 'text="Widget update 1"' 1
wait_ui 'text="Row 1, update 1"' 1
pass "widgets: provider click, RemoteViews update, and collection refresh are delivered"

ui="$(dump_ui)"
read -r wx1 wy1 wx2 wy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
"$adb" -s "$serial" shell input swipe $(((wx1 + wx2) / 2)) $(((wy1 + wy2) / 2)) $(((wx1 + wx2) / 2)) $(((wy1 + wy2) / 2)) 800
filter_widgets
wait_ui 'text="Fixture collection widget"' 1
tap_pattern 'text="Fixture collection widget"'
accept_widget_bind
sleep 2
ui="$(dump_ui)"
widget_count="$(grep -o 'text="Widget update 1"' <<<"$ui" | wc -l)"
[[ "$widget_count" -ge 2 ]] || fail "second widget instance was not rendered"
pass "widgets: duplicate provider instances remain independent launcher items"

long_press_pattern_top 'content-desc="Fixture collection widget, page 1'
wait_ui 'content-desc="Resize right"' 1
ui="$(dump_ui)"
read -r rx1 ry1 rx2 ry2 < <(read_bounds "$ui" 'content-desc="Resize right"')
drag_coordinates $(((rx1 + rx2) / 2)) $(((ry1 + ry2) / 2)) $(((rx1 + rx2) / 2 + 80)) $(((ry1 + ry2) / 2))
sleep 2
long_press_pattern_top 'content-desc="Fixture collection widget, page 1'
wait_ui 'content-desc="Move"' 1
drag_from_to 'content-desc="Move"' 'content-desc="Empty, page 1'
sleep 2
wait_ui 'text="Widget update 1"' 1
pass "widgets: long-press controls resize and move through snapped reducer commands"

ui="$(dump_ui)"
read -r wx1 wy1 wx2 wy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
"$adb" -s "$serial" shell input swipe $(((wx1 + wx2) / 2)) $(((wy1 + wy2) / 2)) $(((wx1 + wx2) / 2)) $(((wy1 + wy2) / 2)) 800
filter_widgets
wait_ui 'text="Fixture configurable widget"' 1
tap_pattern 'text="Fixture configurable widget"'
accept_widget_bind
wait_resumed 'com.caniko.cenix.fixture/.FixtureWidgetConfigActivity'
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
wait_resumed 'com.caniko.cenix/.HomeActivity'
wait_ui 'text="Configured widget' 0
pass "widgets: cancelled required configuration rolls back allocation and placement"

ui="$(dump_ui)"
read -r wx1 wy1 wx2 wy2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
"$adb" -s "$serial" shell input swipe $(((wx1 + wx2) / 2)) $(((wy1 + wy2) / 2)) $(((wx1 + wx2) / 2)) $(((wy1 + wy2) / 2)) 800
filter_widgets
wait_ui 'text="Fixture configurable widget"' 1
tap_pattern 'text="Fixture configurable widget"'
accept_widget_bind
wait_resumed 'com.caniko.cenix.fixture/.FixtureWidgetConfigActivity'
tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/complete_widget_config"'
wait_resumed 'com.caniko.cenix/.HomeActivity'
wait_ui 'text="Configured widget' 1
pass "widgets: required configuration commits only after provider acceptance"

open_fixture_control
tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/pin_widget"'
wait_ui 'text="Add widget to Home?"' 1
tap_pattern 'resource-id="com.caniko.cenix:id/pin_cancel"'
go_home
pass "widgets: incoming pin cancellation writes no placement"
open_fixture_control
tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/pin_widget"'
wait_ui 'text="Add widget to Home?"' 1
tap_pattern 'resource-id="com.caniko.cenix:id/pin_confirm"'
accept_widget_bind
go_home
wait_ui 'text="Widget update 1"' 1
pass "widgets: explicit incoming pin acceptance binds and commits"

"$adb" -s "$serial" uninstall com.caniko.cenix.fixture >/dev/null
wait_ui 'text="Widget unavailable"' 1
tap_pattern 'content-desc="Remove"'
sleep 1
pass "widgets: provider removal renders a removable placeholder"
fi

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
apply_app_rtl
"$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null
go_home
wait_ui 'Emergency mode' 1
ui="$(dump_ui)"
if ! echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/appLabel"'; then
  set_search "Fixture"
  wait_ui 'text="Cenix Fixture"' 1
  clear_search
  hide_keyboard
fi
pass "native-unavailable APK starts in Kotlin emergency and lists apps"
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
pass "native-unavailable Kotlin search works"
hide_keyboard
tap_pattern 'resource-id="com.caniko.cenix:id/appLabel"'
wait_resumed 'com.caniko.cenix.fixture/.FixtureActivity'
go_home
wait_ui 'Emergency mode' 1
pass "native-unavailable launch + HOME still works"
tap_pattern 'resource-id="com.caniko.cenix:id/retryNative"'
sleep 1
wait_ui 'Emergency mode' 1
pass "retry-native does not report success while libcenix_ffi.so is absent"

echo "emulator conformance suite $suite passed on $serial (Android API 35 $image_kind x86_64, not GrapheneOS/mustang)"
