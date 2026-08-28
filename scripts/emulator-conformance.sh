#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
suite="full"
if [[ "${1:-}" == "--suite" ]]; then
  suite="${2:-}"
  shift 2
fi
[[ $# == 0 ]] || { echo "usage: $0 [--suite core|workspace|folders|shortcuts|widgets|profiles|settings|packages|notifications|appearance|backup|full]" >&2; exit 2; }
case "$suite" in core|workspace|folders|shortcuts|widgets|profiles|settings|packages|notifications|appearance|backup|full) ;; *) echo "unknown suite: $suite" >&2; exit 2 ;; esac
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
adb="${ADB:-adb}"
run_id="${CENIX_RUN_ID:-$$-$(date +%s)}"
started_at="$(date +%s)"
art="${CENIX_ARTIFACTS:-$(mktemp -d /tmp/cenix-conformance.XXXXXX)}"
workspace_rtl=0
work_profile_id=""
private_profile_id=""
private_profile_status="not-attempted"
target_avd=""
target_serial=""
target_started=0
target_created_avd=0
mkdir -p "$art"
export CENIX_GIT_COMMIT="${CENIX_GIT_COMMIT:-$(git -C "$root" rev-parse HEAD)}"
avd="${CENIX_AVD:-cenix-ci-$run_id}"
if [[ "$avd" == "cenix-api35" && "${CENIX_ALLOW_SHARED_AVD:-0}" != "1" ]]; then
  echo "refusing shared AVD cenix-api35; set CENIX_ALLOW_SHARED_AVD=1 to override" >&2
  exit 1
fi
default_image="$sdk/system-images/android-35/default/x86_64"
compat_image="$sdk/system-images/android-35/google_apis/x86_64"
sysdir="${CENIX_SYSTEM_IMAGE:-$default_image}"
if [[ ! -d "$sysdir" && "${CENIX_ALLOW_GOOGLE_APIS:-0}" == "1" ]]; then
  sysdir="$compat_image"
fi
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
  echo "emulator suite must run through: nix develop .#emulator-aosp" >&2
  exit 1
fi
if [[ "$image_kind" != "aosp" && "${CENIX_ALLOW_GOOGLE_APIS:-0}" != "1" ]]; then
  echo "refusing non-AOSP image $sysdir; set CENIX_ALLOW_GOOGLE_APIS=1 for compatibility-only evidence" >&2
  exit 1
fi
emulator_bin="${EMULATOR:-$sdk/emulator/emulator}"
if [[ ! -x "$emulator_bin" ]]; then
  echo "missing emulator binary $emulator_bin; use: nix develop .#emulator" >&2
  exit 1
fi

if [[ -z "${CENIX_AVD:-}" ]]; then
  export ANDROID_AVD_HOME="$art/avd"
  export ANDROID_EMULATOR_HOME="$art/emu-home"
else
  export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$art/avd}"
  export ANDROID_EMULATOR_HOME="${ANDROID_EMULATOR_HOME:-$art/emu-home}"
fi
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_EMULATOR_HOME"
image_pkg="system-images;android-35;google_apis;x86_64"
if [[ "$image_kind" == "aosp" ]]; then
  image_pkg="system-images;android-35;default;x86_64"
fi
avdmanager="$(echo "$sdk"/cmdline-tools/*/bin/avdmanager | awk '{print $1}')"
created_avd=0
if ! "$emulator_bin" -list-avds | grep -qx "$avd"; then
  if [[ ! -x "$avdmanager" ]]; then
    echo "no avdmanager under $sdk/cmdline-tools; use: nix develop .#emulator" >&2
    exit 1
  fi
  echo no | "$avdmanager" create avd -f -n "$avd" -k "$image_pkg" >/dev/null
  created_avd=1
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
  local wanted="${1:-$avd}" serial name
  while read -r serial _; do
    [[ -z "${serial:-}" ]] && continue
    name="$("$adb" -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -n1 || true)"
    if [[ "$name" == "$wanted" ]]; then
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
cleanup() {
  [[ -n "$serial" && -n "$private_profile_id" ]] && "$adb" -s "$serial" shell pm remove-user "$private_profile_id" >/dev/null 2>&1 || true
  [[ -n "$serial" && -n "$work_profile_id" ]] && "$adb" -s "$serial" shell pm remove-user "$work_profile_id" >/dev/null 2>&1 || true
  if [[ "$started" != "0" ]]; then
    kill "$started" 2>/dev/null || true
    wait "$started" 2>/dev/null || true
  fi
  if [[ "$target_started" != "0" ]]; then
    kill "$target_started" 2>/dev/null || true
    wait "$target_started" 2>/dev/null || true
  fi
  [[ "$target_created_avd" == "1" ]] && "$avdmanager" delete avd -n "$target_avd" >/dev/null 2>&1 || true
  [[ -z "${CENIX_AVD:-}" && "$created_avd" == "1" ]] && "$avdmanager" delete avd -n "$avd" >/dev/null 2>&1 || true
}
trap cleanup EXIT
emu_port="${CENIX_EMU_PORT:-$((5554 + RANDOM % 15 * 2))}"
rtl_boot=()
if [[ "${CENIX_FORCE_RTL:-false}" == "true" ]]; then
  rtl_boot=(-prop debug.force_rtl=true)
fi
if [[ -z "$serial" ]]; then
  "$emulator_bin" -avd "$avd" -port "$emu_port" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect "${rtl_boot[@]}" >"$art/emulator.log" 2>&1 &
  started=$!
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
configure_device() {
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
}

configure_device

pass() { echo "PASS: $1"; }
fail() {
  echo "FAIL: $1" >&2
  printf 'outcome=failed\nduration_seconds=%s\n' "$(( $(date +%s) - started_at ))" >>"$art/metadata.txt"
  "$adb" -s "$serial" logcat -d >"$art/logcat.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell uiautomator dump /sdcard/cenix-ui.xml >/dev/null 2>&1 || true
  "$adb" -s "$serial" pull /sdcard/cenix-ui.xml "$art/ui.xml" >/dev/null 2>&1 || true
  "$adb" -s "$serial" exec-out screencap -p >"$art/screen.png" 2>/dev/null || true
  "$adb" -s "$serial" shell dumpsys activity >"$art/activity.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell dumpsys package com.caniko.cenix >"$art/package.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell pm list users >"$art/users.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell dumpsys user >"$art/user-state.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell cmd role get-role-holders android.app.role.HOME >"$art/role.txt" 2>/dev/null || true
  "$adb" -s "$serial" shell dumpsys package com.caniko.cenix >"$art/package.txt" 2>/dev/null || true
  printf '%s\n' "$CENIX_GIT_COMMIT" >"$art/git-commit.txt"
  echo "artifacts: $art" >&2
  exit 1
}

snapshot_restore_state() {
  local label="$1" expected_generation="$2" expected_phase="$3" stop_mode="${4:-force}" suffix
  local db="$art/$label.db"
  if [[ "$stop_mode" == "kill" ]]; then
    "$adb" -s "$serial" shell am kill com.caniko.cenix
  else
    "$adb" -s "$serial" shell am force-stop com.caniko.cenix
  fi
  for suffix in "" -wal -shm; do
    if "$adb" -s "$serial" shell run-as com.caniko.cenix test -f "databases/cenix.db$suffix"; then
      "$adb" -s "$serial" exec-out run-as com.caniko.cenix cat "databases/cenix.db$suffix" >"$db$suffix"
    fi
  done
  [[ -s "$db" ]] || fail "$label Room database was unavailable"
  python3 - "$db" "$expected_generation" "$expected_phase" >"$art/$label.txt" <<'PY' || fail "$label Room restore state was unexpected"
import sqlite3, sys
path, expected_generation, expected_phase = sys.argv[1:]
db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
generation = db.execute("select generation from workspace_metadata where singletonId=1").fetchone()[0]
row = db.execute("select phase from pending_restore_operations where singletonId=1").fetchone()
phase = row[0] if row else "none"
print(f"generation={generation}\nphase={phase}")
assert (
    expected_generation == "*"
    or expected_generation == "positive" and generation > 0
    or generation == int(expected_generation)
), (generation, expected_generation)
assert phase == expected_phase, (phase, expected_phase)
PY
}

resumed() {
  "$adb" -s "$serial" shell dumpsys activity activities | tr -d '\r' | grep 'topResumedActivity' || true
}

wait_resumed() {
  local component="$1"
  for _ in $(seq 1 30); do
    if resumed | grep -q "$component"; then
      return 0
    fi
    sleep 1
  done
  fail "not resumed: $component"
}

dump_ui() {
  local xml keep_ime=0
  [[ "${1:-}" == "ime" ]] && keep_ime=1
  for _ in $(seq 1 15); do
    "$adb" -s "$serial" shell rm -f /sdcard/cenix-ui.xml
    "$adb" -s "$serial" shell uiautomator dump /sdcard/cenix-ui.xml >/dev/null 2>&1 || true
    if "$adb" -s "$serial" shell test -s /sdcard/cenix-ui.xml; then
      xml="$("$adb" -s "$serial" shell cat /sdcard/cenix-ui.xml)"
      if grep -q 'package="com.caniko.cenix"' <<<"$xml"; then
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
  for _ in $(seq 1 15); do
    "$adb" -s "$serial" shell rm -f /sdcard/cenix-any-ui.xml
    "$adb" -s "$serial" shell uiautomator dump /sdcard/cenix-any-ui.xml >/dev/null 2>&1 || true
    if "$adb" -s "$serial" shell test -s /sdcard/cenix-any-ui.xml; then
      xml="$("$adb" -s "$serial" shell cat /sdcard/cenix-any-ui.xml)"
      if grep -q '<node ' <<<"$xml"; then
        printf '%s\n' "$xml"
        return 0
      fi
    fi
    sleep 1
  done
  fail "uiautomator dump failed"
}

wait_ui() {
  local needle="$1" want="${2:-1}" xml
  for _ in $(seq 1 20); do
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
  for _ in 1 2 3; do
    ui="$(dump_ui)"
    grep -q 'resource-id="com.caniko.cenix:id/searchField"' <<<"$ui" && return 0
    if ! grep -q 'resource-id="com.caniko.cenix:id/launcherRoot"' <<<"$ui"; then
      "$adb" -s "$serial" shell input keyevent KEYCODE_BACK
      go_home
      sleep 1
      continue
    fi
    swipe_surface up
    sleep 1
  done
  wait_ui 'resource-id="com.caniko.cenix:id/searchField"' 1
}

close_all_apps() {
  hide_keyboard
  "$adb" -s "$serial" shell input keyevent KEYCODE_BACK
  wait_ui 'resource-id="com.caniko.cenix:id/searchField"' 0
}

clear_search() {
  focus_search
  "$adb" -s "$serial" shell input keyevent KEYCODE_MOVE_END
  for _ in $(seq 1 80); do
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
  ui="$(dump_ui)"
  if grep -q 'text="Add widget"' <<<"$ui"; then
    tap_pattern 'text="Add widget"'
  fi
  wait_ui 'resource-id="com.caniko.cenix:id/widget_search"' 1
  ui="$(dump_ui)"
  read -r x1 y1 x2 y2 < <(read_bounds "$ui" 'resource-id="com.caniko.cenix:id/widget_search"')
  tap_bounds "$x1" "$y1" "$x2" "$y2"
  "$adb" -s "$serial" shell input text Fixture
  sleep 1
  hide_keyboard
}

accept_widget_bind() {
  local ui x1 y1 x2 y2
  for _ in $(seq 1 20); do
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
  for _ in $(seq 1 30); do
    "$adb" -s "$serial" shell input keyevent KEYCODE_HOME
    resumed | grep -q 'com.caniko.cenix/.HomeActivity' && return
    if role_holders | grep -q 'com.caniko.cenix' && resumed | grep -q 'com.android.launcher3/'; then
      "$adb" -s "$serial" shell am force-stop com.android.launcher3
    fi
    sleep 1
  done
  fail 'not resumed: com.caniko.cenix/.HomeActivity'
}

open_fixture_control() {
  "$adb" -s "$serial" shell am force-stop com.caniko.cenix.fixture
  "$adb" -s "$serial" shell am start -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
  wait_resumed 'com.caniko.cenix.fixture/.FixtureActivity'
}

set_fixture_wallpaper() {
  "$adb" -s "$serial" shell am start -S -n com.caniko.cenix.fixture/.FixtureActivity --es wallpaper-command "$1" >/dev/null
  sleep 2
}

open_launcher_settings() {
  open_all_apps
  tap_pattern 'resource-id="com.caniko.cenix:id/launcherSettings"'
  wait_resumed 'com.caniko.cenix/.LauncherSettingsActivity'
  "$adb" -s "$serial" shell input swipe 8 180 8 560 400
  "$adb" -s "$serial" shell input swipe 8 180 8 560 400
}

capture_settings_appearance() {
  local name="$1" want="$2"
  "$adb" -s "$serial" exec-out screencap -p >"$art/wallpaper-$name.png"
  "$adb" -s "$serial" shell dumpsys activity top >"$art/wallpaper-$name-activity.txt"
  [[ -s "$art/wallpaper-$name.png" ]] || fail "empty $name appearance screenshot"
  if [[ "$want" == "night" ]]; then
    grep -q 'mLastConfigurationFromResources=.* notnight ' "$art/wallpaper-$name-activity.txt" && fail "$name wallpaper still reported notnight"
    grep -q 'mLastConfigurationFromResources=.* night ' "$art/wallpaper-$name-activity.txt" || fail "$name wallpaper did not produce a night settings configuration"
  else
    grep -q 'mLastConfigurationFromResources=' "$art/wallpaper-$name-activity.txt" || fail "$name wallpaper settings configuration was unavailable"
    if grep -q 'mLastConfigurationFromResources=.* night ' "$art/wallpaper-$name-activity.txt"; then
      fail "$name wallpaper still reported night"
    fi
  fi
}

apply_fixture_wallpaper_appearance() {
  set_fixture_wallpaper "$1"
  "$adb" -s "$serial" shell am force-stop com.caniko.cenix
  go_home
  open_launcher_settings
  capture_settings_appearance "$1" "$2"
}

reboot_emulator() {
  "$adb" -s "$serial" reboot
  for _ in $(seq 1 60); do
    if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      break
    fi
    sleep 5
  done
  if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
    fail "reboot probe: emulator did not come back"
  fi
  configure_device
}

# ponytail: adb reboot races boot/serial/HOME; opt-in with CENIX_APPEARANCE_REBOOT=1
probe_appearance_reboot() {
  local holders
  holders="$(role_holders)"
  echo "$holders" | grep -q 'com.caniko.cenix' || fail "reboot probe: HOME missing before reboot"
  wait_resumed 'com.caniko.cenix/.LauncherSettingsActivity'
  "$adb" -s "$serial" shell dumpsys activity top >"$art/wallpaper-prereboot-activity.txt"
  grep -q 'mLastConfigurationFromResources=.* night ' "$art/wallpaper-prereboot-activity.txt" || fail "reboot probe: expected night settings before reboot"
  "$adb" -s "$serial" exec-out screencap -p >"$art/wallpaper-prereboot.png"
  reboot_emulator
  holders="$(role_holders)"
  echo "$holders" | grep -q 'com.caniko.cenix' || fail "reboot probe: HOME missing after reboot"
  go_home
  open_launcher_settings
  "$adb" -s "$serial" shell dumpsys activity top >"$art/wallpaper-postreboot-activity.txt"
  grep -q 'mLastConfigurationFromResources=.* night ' "$art/wallpaper-postreboot-activity.txt" || fail "reboot probe: dark appearance did not survive reboot"
  "$adb" -s "$serial" exec-out screencap -p >"$art/wallpaper-postreboot.png"
  dump_ui | grep -q "$CENIX_GIT_COMMIT" || fail "reboot probe: settings build identity missing"
  pass "appearance: reboot probe retained HOME, night config, and source commit"
}

role_holders() {
  "$adb" -s "$serial" shell cmd role get-role-holders android.app.role.HOME | tr -d '\r'
}

setup_profiles() {
  local output
  if ! output="$("$adb" -s "$serial" shell pm create-user --profileOf 0 --managed CenixConformanceWork 2>&1 | tr -d '\r')"; then
    printf '%s\n' "$output" >"$art/work-profile-create.txt"
    fail "managed profile creation failed"
  fi
  printf '%s\n' "$output" >"$art/work-profile-create.txt"
  work_profile_id="$(sed -n 's/.*user id \([0-9][0-9]*\).*/\1/p' <<<"$output")"
  [[ -n "$work_profile_id" ]] || fail "managed profile creation returned no user id: $output"
  "$adb" -s "$serial" shell am start-user -w "$work_profile_id" >/dev/null || fail "managed profile did not start"

  if output="$("$adb" -s "$serial" shell pm create-user --profileOf 0 --user-type android.os.usertype.profile.PRIVATE CenixConformancePrivate 2>&1 | tr -d '\r')"; then
    printf '%s\n' "$output" >"$art/private-profile-create.txt"
    private_profile_id="$(sed -n 's/.*user id \([0-9][0-9]*\).*/\1/p' <<<"$output")"
    if [[ -n "$private_profile_id" ]] && "$adb" -s "$serial" shell am start-user -w "$private_profile_id" >/dev/null 2>&1; then
      private_profile_status="created"
    else
      private_profile_status="create-returned-no-usable-profile"
      [[ -n "$private_profile_id" ]] && "$adb" -s "$serial" shell pm remove-user "$private_profile_id" >/dev/null 2>&1 || true
      private_profile_id=""
    fi
  else
    printf '%s\n' "$output" >"$art/private-profile-create.txt"
    private_profile_status="unsupported-by-image"
  fi
  "$adb" -s "$serial" shell pm list users >"$art/users.txt"
  "$adb" -s "$serial" shell dumpsys user >"$art/user-state.txt"
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

if [[ "$suite" == "profiles" || "$suite" == "full" ]]; then
  setup_profiles
  holders="$(role_holders)"
  echo "$holders" | grep -q 'com.caniko.cenix' || fail "profile creation dropped HOME role: $holders"
  hidden_profiles_permission="$("$adb" -s "$serial" shell dumpsys package com.caniko.cenix | grep 'android.permission.ACCESS_HIDDEN_PROFILES:' | tr -d '\r' || true)"
  printf '%s\n' "${hidden_profiles_permission:-not-reported}" >"$art/hidden-profiles-permission.txt"
  if [[ "$private_profile_status" == "created" && "$hidden_profiles_permission" != *"granted=true"* ]]; then
    private_profile_status="permission-not-granted"
  fi
  {
    echo "work_profile_id=$work_profile_id"
    echo "private_profile_status=$private_profile_status"
    echo "private_profile_id=${private_profile_id:-none}"
  } >>"$art/metadata.txt"
fi

"$adb" -s "$serial" install -r -t "$root/android/fixture-secondary/build/outputs/apk/debug/fixture-secondary-debug.apk"
open_all_apps
wait_ui 'resource-id="com.caniko.cenix:id/appLabel"' 1
pass "shell: swipe up opens All Apps"
clear_search
hide_keyboard
ui="$(dump_ui)"
echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/appLabel"' || fail "catalog empty"
pass "discovery: nonempty catalog, Cenix hidden"

"$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
"$adb" -s "$serial" shell am start -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
go_home
wait_ui 'content-desc="Cenix Fixture, page 1' 1
pass "automatic placement: confirmed new install is placed without a duplicate callback path"
open_all_apps
tap_pattern 'resource-id="com.caniko.cenix:id/launcherSettings"'
wait_resumed 'com.caniko.cenix/.LauncherSettingsActivity'
"$adb" -s "$serial" shell input swipe 160 560 160 180 400
"$adb" -s "$serial" shell input swipe 160 560 160 180 400
tap_pattern 'resource-id="com.caniko.cenix:id/resetLauncher"'
sleep 1
tap_any_pattern 'resource-id="android:id/button1"'
wait_ui 'text="Local state reset"' 1
"$adb" -s "$serial" shell input swipe 8 180 8 560 400
"$adb" -s "$serial" shell input swipe 8 180 8 560 400
tap_pattern 'resource-id="com.caniko.cenix:id/autoAddApps"'
ui="$(dump_ui)"
echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/autoAddApps"[^>]*checked="false"' || fail "automatic placement preference did not disable"
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

if [[ "$suite" == "settings" || "$suite" == "appearance" || "$suite" == "notifications" || "$suite" == "full" ]]; then
  open_all_apps
  tap_pattern 'resource-id="com.caniko.cenix:id/launcherSettings"'
  wait_resumed 'com.caniko.cenix/.LauncherSettingsActivity'
  "$adb" -s "$serial" shell input swipe 8 180 8 560 400
  "$adb" -s "$serial" shell input swipe 8 180 8 560 400
  ui="$(dump_ui)"
  for control in wallpaper themedIcons notificationDots autoAddApps; do
    echo "$ui" | grep -q "resource-id=\"com.caniko.cenix:id/$control\"" || fail "P5B setting missing: $control"
  done
  "$adb" -s "$serial" shell input swipe 160 560 160 180 400
  ui="$(dump_ui)"
  for grid in grid_2_by_2 grid_3_by_3 grid_4_by_4; do
    echo "$ui" | grep -q "resource-id=\"com.caniko.cenix:id/$grid\"" || fail "compatible setting missing: $grid"
  done
  echo "$ui" | grep -q 'grid_4_by_5\|grid_5_by_5' && fail "incompatible phone grid was selectable"
  echo "$ui" | grep -q "$CENIX_GIT_COMMIT" || fail "settings build identity missing exact source commit"
  pass "settings: finite compatible phone grids and build identity are visible"

  tap_pattern 'resource-id="com.caniko.cenix:id/grid_3_by_3"'
  wait_ui 'text="Grid applied"' 1
  device_settings_ui="$(dump_ui)"
  echo "$device_settings_ui" | grep -q 'resource-id="com.caniko.cenix:id/grid_3_by_3"[^>]*checked="true"' || fail "selected grid not checked"
  "$adb" -s "$serial" shell am force-stop com.caniko.cenix
  go_home
  sleep 2
  open_all_apps
  tap_pattern 'resource-id="com.caniko.cenix:id/launcherSettings"'
  wait_resumed 'com.caniko.cenix/.LauncherSettingsActivity'
  ui="$(dump_ui)"
  echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/grid_3_by_3"[^>]*checked="true"' || fail "grid selection did not survive recreation"
  pass "settings: selected grid survives process recreation"

  "$adb" -s "$serial" shell input swipe 160 560 160 180 400
  tap_pattern 'resource-id="com.caniko.cenix:id/resetLauncher"'
  sleep 1
  ui="$(dump_any_ui)"
  echo "$ui" | grep -q 'Remove all local pages' || fail "reset confirmation did not open"
  tap_any_pattern 'resource-id="android:id/button2"'
  sleep 1
  ui="$(dump_ui)"
  echo "$ui" | grep -q 'Remove all local pages' && fail "reset confirmation did not close"
  pass "settings: reset requires confirmation and cancellation preserves state"
  tap_pattern 'resource-id="com.caniko.cenix:id/grid_4_by_4"'
  wait_ui 'text="Grid applied"' 1
  go_home
  ui="$(dump_ui)"
  echo "$ui" | grep -q 'column 4' || fail "restored 4 x 4 grid did not render"
  pass "settings: shrink, repeat-safe persistence, and expansion render through Room/Rust"
fi

if [[ "$suite" == "notifications" || "$suite" == "full" ]]; then
  "$adb" -s "$serial" shell pm grant com.caniko.cenix.fixture android.permission.POST_NOTIFICATIONS
  "$adb" -s "$serial" shell cmd notification allow_listener com.caniko.cenix/com.caniko.cenix.CenixNotificationListener
  "$adb" -s "$serial" shell am start -S -n com.caniko.cenix.fixture/.FixtureActivity --es notification-command post >/dev/null
  sleep 1
  "$adb" -s "$serial" shell dumpsys notification --noredact | grep -q 'NotificationRecord.*pkg=com.caniko.cenix.fixture' || fail "fixture notification was not posted"
  go_home
  set_search "Fixture"
  wait_ui 'Cenix Fixture, Personal, Notifications available' 1
  pass "notifications: user-authorized package/profile dot reaches All Apps"
  "$adb" -s "$serial" shell am start -S -n com.caniko.cenix.fixture/.FixtureActivity --es notification-command cancel >/dev/null
  go_home
  set_search "Fixture"
  wait_ui 'Cenix Fixture, Personal, Notifications available' 0
  pass "notifications: removal clears transient state"
  "$adb" -s "$serial" shell am start -S -n com.caniko.cenix.fixture/.FixtureActivity --es notification-command summary >/dev/null
  go_home
  set_search "Fixture"
  wait_ui 'Cenix Fixture, Personal, Notifications available' 0
  pass "notifications: group summaries are ineligible"
  "$adb" -s "$serial" shell cmd notification disallow_listener com.caniko.cenix/com.caniko.cenix.CenixNotificationListener
  wait_ui 'Cenix Fixture, Personal, Notifications available' 0
  pass "notifications: authorization revocation clears dots"
  "$adb" -s "$serial" shell am start -S -n com.caniko.cenix.fixture/.FixtureActivity --es notification-command cancel >/dev/null
  go_home
  clear_search
fi

if [[ "$suite" == "appearance" || "$suite" == "full" ]]; then
  open_launcher_settings
  tap_pattern 'resource-id="com.caniko.cenix:id/themedIcons"'
  ui="$(dump_ui)"
  echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/themedIcons"[^>]*checked="true"' || fail "themed icon preference did not enable"
  "$adb" -s "$serial" shell am force-stop com.caniko.cenix
  go_home
  open_launcher_settings
  ui="$(dump_ui)"
  echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/themedIcons"[^>]*checked="true"' || fail "themed icon preference did not survive recreation"
  tap_pattern 'resource-id="com.caniko.cenix:id/wallpaper"'
  sleep 2
  resumed | grep -q 'com.caniko.cenix/.LauncherSettingsActivity' && fail "wallpaper action did not leave Cenix"
  pass "appearance: system-owned wallpaper action opens and themed preference survives recreation"
  "$adb" -s "$serial" shell cmd wallpaper help >"$art/wallpaper-shell-capability.txt" 2>&1 || true
  "$adb" -s "$serial" shell input keyevent KEYCODE_BACK
  go_home
  apply_fixture_wallpaper_appearance light notnight
  apply_fixture_wallpaper_appearance dark night
  cmp -s "$art/wallpaper-light.png" "$art/wallpaper-dark.png" && fail "light and dark appearance screenshots were identical"
  sha256sum "$art/wallpaper-light.png" "$art/wallpaper-dark.png" >"$art/wallpaper-screenshots.sha256"
  pass "appearance: committed fixture drives light and dark settings appearance"
  if [[ "${CENIX_APPEARANCE_REBOOT:-0}" == "1" ]]; then
    probe_appearance_reboot
  fi
  go_home
fi

if [[ "$suite" == "packages" || "$suite" == "full" ]]; then
  "$adb" -s "$serial" shell pm suspend --user 0 com.caniko.cenix.fixture >/dev/null || fail "fixture suspend failed"
  go_home
  set_search "Fixture"
  wait_ui 'text="Suspended"' 1
  wait_ui 'text="Cenix Fixture"' 1
  pass "packages: suspended state retains and disables the application presentation"
  "$adb" -s "$serial" shell pm unsuspend --user 0 com.caniko.cenix.fixture >/dev/null || fail "fixture unsuspend failed"
  wait_ui 'text="Suspended"' 0

  "$adb" -s "$serial" shell pm disable-user --user 0 com.caniko.cenix.fixture >/dev/null || fail "fixture disable failed"
  wait_ui 'text="Disabled"' 1
  wait_ui 'text="Cenix Fixture"' 1
  pass "packages: disabled state retains application identity without launching"
  "$adb" -s "$serial" shell pm enable --user 0 com.caniko.cenix.fixture >/dev/null || fail "fixture enable failed"
  wait_ui 'text="Disabled"' 0
  "$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk" >/dev/null
  wait_ui 'text="Cenix Fixture"' 1
  pass "packages: replacement update completes without deleting presentation"
  printf '%s\n' \
    'ARCHIVED=UNKNOWN_REQUIRES_SPIKE: no stable AOSP emulator shell setup' \
    'TEMPORARILY_UNAVAILABLE=UNKNOWN_REQUIRES_SPIKE: no removable-app-volume fixture' \
    'INSTALL_PROGRESS=UNKNOWN_REQUIRES_SPIKE: local adb replacement completes before observable progress' \
    >"$art/package-capabilities.txt"
fi

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
sleep 1
ui="$(dump_ui)"
if grep -q 'content-desc="Page 2 of 2"' <<<"$ui"; then
  drag_to_workspace_edge 'content-desc="Cenix Fixture, page 2' prev
fi
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
sleep 1
tap_pattern 'content-desc="Utilities, folder,'
sleep 1
ui="$(dump_ui)"
if ! grep -q 'resource-id="com.caniko.cenix:id/folder_popup"' <<<"$ui"; then
  tap_pattern 'content-desc="Utilities, folder,'
  sleep 1
  ui="$(dump_ui)"
fi
if ! grep -q 'content-desc="Cenix Auxiliary, rank 5"' <<<"$ui"; then
  "$adb" -s "$serial" shell input keyevent KEYCODE_BACK
  wait_ui 'resource-id="com.caniko.cenix:id/folder_popup"' 0
  open_all_apps
  set_search "Auxiliary"
  wait_ui 'text="Cenix Auxiliary"' 1
  drag_from_to 'text="Cenix Auxiliary".*resource-id="com.caniko.cenix:id/appLabel"' 'content-desc="Utilities, folder,'
  sleep 1
  tap_pattern 'content-desc="Utilities, folder,'
fi
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

long_press_pattern_top 'content-desc="Fixture collection widget, page 1'
wait_ui 'content-desc="Resize right"' 1
ui="$(dump_ui)"
read -r wx1 _ wx2 _ < <(read_bounds "$ui" 'content-desc="Fixture collection widget, page 1')
read -r rx1 ry1 rx2 ry2 < <(read_bounds "$ui" 'content-desc="Resize right"')
resize_dx=80
(( workspace_rtl == 1 )) && resize_dx=-80
"$adb" -s "$serial" shell input swipe $(((rx1 + rx2) / 2)) $(((ry1 + ry2) / 2)) $(((rx1 + rx2) / 2 + resize_dx)) $(((ry1 + ry2) / 2)) 600
sleep 2
ui="$(dump_ui)"
read -r resized_x1 _ resized_x2 _ < <(read_bounds "$ui" 'content-desc="Fixture collection widget, page 1')
(( resized_x2 - resized_x1 > wx2 - wx1 )) || fail "widget resize did not expand"
long_press_pattern_top 'content-desc="Fixture collection widget, page 1'
wait_ui 'content-desc="Resize right"' 1
ui="$(dump_ui)"
read -r rx1 ry1 rx2 ry2 < <(read_bounds "$ui" 'content-desc="Resize right"')
"$adb" -s "$serial" shell input swipe $(((rx1 + rx2) / 2)) $(((ry1 + ry2) / 2)) $(((rx1 + rx2) / 2 - resize_dx)) $(((ry1 + ry2) / 2)) 600
sleep 2
pass "widgets: horizontal resize follows logical grid direction"

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
wait_ui 'content-desc="Move"' 1
drag_from_to 'content-desc="Move"' 'content-desc="Empty, page 1'
sleep 2
wait_ui 'text="Widget update 1"' 1
pass "widgets: long-press controls move through snapped reducer commands"

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
for _ in $(seq 1 8); do
  ui="$(dump_ui)"
  grep -q 'text="Widget unavailable"' <<<"$ui" || break
  if grep -q 'content-desc="Remove"' <<<"$ui"; then
    tap_pattern 'content-desc="Remove"'
  else
    long_press_pattern_top 'content-desc="Widget unavailable, page 1'
    wait_ui 'content-desc="Remove widget"' 1
    tap_pattern 'content-desc="Remove widget"'
  fi
  sleep 1
done
pass "widgets: provider removal renders a removable placeholder"
fi

if [[ "$suite" == "profiles" || "$suite" == "full" ]]; then
fixture_apk="$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk"
aux_apk="$root/android/fixture-secondary/build/outputs/apk/debug/fixture-secondary-debug.apk"
"$adb" -s "$serial" install -r -t "$fixture_apk" >/dev/null
"$adb" -s "$serial" shell pm install-existing --user "$work_profile_id" com.caniko.cenix.fixture >"$art/work-install.txt" || fail "fixture install into managed profile failed"
"$adb" -s "$serial" shell dpm set-profile-owner --user "$work_profile_id" com.caniko.cenix.fixture/.FixtureAdminReceiver >"$art/work-profile-owner.txt" || fail "test DPC profile-owner setup failed"
"$adb" -s "$serial" shell am start --user "$work_profile_id" -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
go_home
open_all_apps
tap_pattern 'resource-id="com.caniko.cenix:id/workTab"'
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
tap_pattern 'text="Cenix Fixture".*resource-id="com.caniko.cenix:id/appLabel"'
wait_resumed 'com.caniko.cenix.fixture/.FixtureActivity'
resumed | grep -q "u$work_profile_id " || fail "fixture launched outside managed profile: $(resumed)"
pass "profiles: same package launches with managed-profile UserHandle"

go_home
ui="$(dump_ui)"
read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
open_all_apps
tap_pattern 'resource-id="com.caniko.cenix:id/workTab"'
set_search "Fixture"
wait_ui 'text="Cenix Fixture"' 1
long_press_pattern_top 'text="Cenix Fixture".*resource-id="com.caniko.cenix:id/appLabel"'
wait_ui 'text="Manifest action"' 1
pass "profiles: managed-profile dynamic and manifest shortcuts resolve"
"$adb" -s "$serial" shell input keyevent KEYCODE_BACK
wait_ui 'resource-id="com.caniko.cenix:id/context_popup"' 0
drag_pattern_to_bounds 'text="Cenix Fixture".*resource-id="com.caniko.cenix:id/appLabel"' $(((px1 + px2) / 2)) $(((py1 + py2) / 2))
wait_ui 'content-desc="Cenix Fixture, page 1' 1
pass "profiles: managed-profile application persists with stable profile identity"

ui="$(dump_ui)"
read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
"$adb" -s "$serial" shell input swipe $(((px1 + px2) / 2)) $(((py1 + py2) / 2)) $(((px1 + px2) / 2)) $(((py1 + py2) / 2)) 800
filter_widgets
wait_ui 'text="Fixture collection widget - Work"' 1
tap_pattern 'text="Fixture collection widget - Work"'
accept_widget_bind
wait_ui 'text="Widget update 0"' 1
pass "profiles: managed-profile widget provider binds and renders"

open_all_apps
tap_pattern 'resource-id="com.caniko.cenix:id/workTab"'
tap_pattern 'resource-id="com.caniko.cenix:id/workProfileToggle"'
wait_ui 'text="Work apps are paused"' 1
go_home
wait_ui 'text="Profile item unavailable"' 1
ui="$(dump_ui)"
echo "$ui" | grep -q 'text="Cenix Fixture"' && fail "managed-profile identity remained visible while quiet"
pass "profiles: quiet mode hides resources and preserves generic workspace placeholders"

open_all_apps
tap_pattern 'resource-id="com.caniko.cenix:id/workTab"'
tap_pattern 'resource-id="com.caniko.cenix:id/workProfileToggle"'
wait_ui 'text="Work apps are available"' 1
wait_ui 'text="Cenix Fixture"' 1
go_home
wait_ui 'content-desc="Cenix Fixture, page 1' 1
wait_ui 'text="Widget update 0"' 1
pass "profiles: unquiet restores managed-profile applications and widgets"

"$adb" -s "$serial" install -r -t "$aux_apk" >/dev/null
"$adb" -s "$serial" shell pm install-existing --user "$work_profile_id" com.caniko.cenix.fixture.secondary >/dev/null
open_all_apps
tap_pattern 'resource-id="com.caniko.cenix:id/workTab"'
set_search "Auxiliary"
wait_ui 'text="Cenix Auxiliary"' 1
"$adb" -s "$serial" shell pm uninstall --user "$work_profile_id" com.caniko.cenix.fixture.secondary >"$art/work-uninstall.txt" || fail "managed-profile-only uninstall failed"
tap_pattern 'resource-id="com.caniko.cenix:id/personalTab"'
wait_ui 'text="Cenix Auxiliary"' 1
tap_pattern 'resource-id="com.caniko.cenix:id/workTab"'
wait_ui 'text="Cenix Auxiliary"' 0
pass "profiles: package removal is isolated to the managed profile"

if [[ "$private_profile_status" == "created" ]]; then
  "$adb" -s "$serial" shell pm uninstall --user 0 com.caniko.cenix.fixture.secondary >/dev/null
  "$adb" -s "$serial" shell am start --user "$private_profile_id" -n com.caniko.cenix.fixture.secondary/.SecondaryFixtureActivity >/dev/null
  go_home
  open_all_apps
  tap_pattern 'resource-id="com.caniko.cenix:id/personalTab"'
  wait_ui 'text="Private space is unlocked"' 1
  wait_ui 'text="Cenix Auxiliary"' 1
  tap_pattern 'text="Cenix Auxiliary".*resource-id="com.caniko.cenix:id/appLabel"'
  wait_resumed 'com.caniko.cenix.fixture.secondary/.SecondaryFixtureActivity'
  resumed | grep -q "u$private_profile_id " || fail "fixture launched outside private profile: $(resumed)"
  go_home
  "$adb" -s "$serial" shell am start --user "$private_profile_id" -n com.caniko.cenix.fixture/.FixtureActivity >/dev/null
  tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/pin_dynamic"'
  sleep 1
  resumed | grep -q 'com.caniko.cenix/.PinShortcutActivity' && fail "private shortcut pin reached confirmation"
  tap_any_pattern 'resource-id="com.caniko.cenix.fixture:id/pin_widget"'
  sleep 1
  resumed | grep -q 'com.caniko.cenix/.PinWidgetActivity' && fail "private widget pin reached confirmation"
  go_home
  open_all_apps
  tap_pattern 'resource-id="com.caniko.cenix:id/personalTab"'
  wait_ui 'text="Cenix Auxiliary"' 1
  long_press_pattern_top 'text="Cenix Auxiliary".*resource-id="com.caniko.cenix:id/appLabel"'
  wait_ui 'resource-id="com.caniko.cenix:id/context_popup"' 1
  ui="$(dump_ui)"
  echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/context_drag"' && fail "private app exposed workspace drag"
  echo "$ui" | grep -q 'resource-id="com.caniko.cenix:id/shortcut_pin"' && fail "private app exposed shortcut pinning"
  "$adb" -s "$serial" shell input keyevent KEYCODE_BACK
  "$adb" -s "$serial" logcat -c
  tap_pattern 'resource-id="com.caniko.cenix:id/privateSpaceToggle"'
  wait_ui 'text="Private space is locked"' 1
  wait_ui 'text="Cenix Auxiliary"' 0
  "$adb" -s "$serial" logcat -d -s cenix | grep -q 'Cenix Auxiliary\|com.caniko.cenix.fixture.secondary' && fail "private identity leaked through Cenix logcat"
  "$adb" -s "$serial" shell run-as com.caniko.cenix sh -c 'grep -R "Cenix Auxiliary\|com.caniko.cenix.fixture.secondary" files/diag' >/dev/null 2>&1 && fail "private identity leaked through diagnostics"
  pass "private: lock hides identities and workspace/pin actions without diagnostic leakage"
  tap_pattern 'resource-id="com.caniko.cenix:id/privateSpaceToggle"'
  wait_ui 'text="Private space is unlocked"' 1
  wait_ui 'text="Cenix Auxiliary"' 1
  pass "private: platform-owned unlock restores private applications"
else
  pass "private: capability gated ($private_profile_status; see private-profile-create.txt and hidden-profiles-permission.txt)"
fi

"$adb" -s "$serial" shell pm remove-user "$work_profile_id" >/dev/null || fail "managed profile teardown failed"
work_profile_id=""
go_home
open_all_apps
wait_ui 'resource-id="com.caniko.cenix:id/workTab"' 0
pass "profiles: permanent profile removal clears profile chrome and durable profile items"
if [[ -n "$private_profile_id" ]]; then
  "$adb" -s "$serial" shell pm remove-user "$private_profile_id" >/dev/null || fail "private profile teardown failed"
  private_profile_id=""
fi
fi

if [[ "$suite" == "backup" || "$suite" == "full" ]]; then
  open_launcher_settings
  "$adb" -s "$serial" shell input swipe 160 560 160 180 400
  "$adb" -s "$serial" shell input swipe 160 560 160 180 400
  tap_pattern 'resource-id="com.caniko.cenix:id/resetLauncher"'
  sleep 1
  tap_any_pattern 'resource-id="android:id/button1"'
  wait_ui 'text="Local state reset"' 1
  "$adb" -s "$serial" shell input swipe 8 180 8 560 400
  "$adb" -s "$serial" shell input swipe 8 180 8 560 400
  ui="$(dump_ui)"
  if grep -q 'resource-id="com.caniko.cenix:id/autoAddApps"[^>]*checked="true"' <<<"$ui"; then
    tap_pattern 'resource-id="com.caniko.cenix:id/autoAddApps"'
  fi
  go_home
  "$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk" >/dev/null
  "$adb" -s "$serial" install -r -t "$root/android/fixture-secondary/build/outputs/apk/debug/fixture-secondary-debug.apk" >/dev/null
  go_home
  ui="$(dump_ui)"
  read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
  set_search "Fixture"
  drag_pattern_to_bounds 'text="Cenix Fixture".*resource-id="com.caniko.cenix:id/appLabel"' $(((px1 + px2) / 2)) $(((py1 + py2) / 2))
  wait_ui 'content-desc="Cenix Fixture, page 1' 1
  ui="$(dump_ui)"
  read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
  set_search "Two"
  drag_pattern_to_bounds 'text="Cenix Fixture Two".*resource-id="com.caniko.cenix:id/appLabel"' $(((px1 + px2) / 2)) $(((py1 + py2) / 2))
  wait_ui 'content-desc="Cenix Fixture Two, page 1' 1
  drag_from_to 'content-desc="Cenix Fixture Two, page 1' 'content-desc="Empty, hotseat'
  wait_ui 'content-desc="Cenix Fixture Two, hotseat' 1
  ui="$(dump_ui)"
  read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
  set_search "Three"
  drag_pattern_to_bounds 'text="Cenix Fixture Three".*resource-id="com.caniko.cenix:id/appLabel"' $(((px1 + px2) / 2)) $(((py1 + py2) / 2))
  wait_ui 'content-desc="Cenix Fixture Three, page 1' 1
  ui="$(dump_ui)"
  read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
  set_search "Four"
  drag_pattern_to_bounds 'text="Cenix Fixture Four".*resource-id="com.caniko.cenix:id/appLabel"' $(((px1 + px2) / 2)) $(((py1 + py2) / 2))
  wait_ui 'content-desc="Cenix Fixture Four, page 1' 1
  drag_from_to 'content-desc="Cenix Fixture Four, page 1' 'content-desc="Cenix Fixture Three, page 1'
  wait_ui 'folder, .* applications' 1
  long_press_pattern 'content-desc="Cenix Fixture, page 1'
  wait_ui 'text="Manifest action"' 1
  ui="$(dump_ui)"
  read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
  drag_pattern_to_bounds 'text="Manifest action"' $(((px1 + px2) / 2)) $(((py1 + py2) / 2))
  wait_ui 'content-desc="Manifest action, page 1' 1
  ui="$(dump_ui)"
  read -r px1 py1 px2 py2 < <(read_bounds "$ui" 'content-desc="Empty, page 1')
  "$adb" -s "$serial" shell input swipe $(((px1 + px2) / 2)) $(((py1 + py2) / 2)) $(((px1 + px2) / 2)) $(((py1 + py2) / 2)) 800
  filter_widgets
  tap_pattern 'text="Fixture collection widget"'
  accept_widget_bind
  wait_ui 'text="Widget update 0"' 1
  drag_to_workspace_edge 'folder, .* applications' next
  wait_ui 'content-desc="Page 2 of 2"' 1
  wait_ui 'folder, .* applications.*page 2' 1
  pass "backup: representative pages, hotseat, folder, shortcut, widget, and preferences are committed"
  "$adb" -s "$serial" shell cmd role remove-role-holder android.app.role.HOME com.caniko.cenix >/dev/null 2>&1 || true
  "$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.android.launcher3 >/dev/null
  "$adb" -s "$serial" shell input keyevent KEYCODE_HOME
  wait_resumed 'com.android.launcher3/'
  snapshot_restore_state backup-source "*" none kill
  source_generation="$(awk -F= '$1 == "generation" {print $2}' "$art/backup-source.txt")"
  [[ "$source_generation" =~ ^[0-9]+$ ]] || fail "source workspace generation was unavailable"

  "$adb" -s "$serial" shell bmgr enable true >"$art/backup-enable.txt"
  "$adb" -s "$serial" shell bmgr transport com.android.localtransport/.LocalTransport >"$art/backup-transport.txt"
  "$adb" -s "$serial" shell bmgr wipe com.android.localtransport/.LocalTransport com.caniko.cenix >"$art/backup-wipe.txt"
  "$adb" -s "$serial" shell bmgr backupnow --monitor com.caniko.cenix >"$art/backup-run.txt" 2>&1 || fail "transport backup command failed"
  grep -q 'Package com.caniko.cenix with result: Success' "$art/backup-run.txt" || fail "transport backup did not succeed"
  "$adb" -s "$serial" shell bmgr list sets >"$art/backup-sets.txt"
  restore_token="$(awk '$1 ~ /^[[:xdigit:]]+:?$/ {sub(/:$/, "", $1); print $1; exit}' "$art/backup-sets.txt")"
  [[ -n "$restore_token" ]] || fail "local transport returned no restore token"
  pass "backup: local transport accepted the canonical full-backup artifact"

  "$adb" -s "$serial" shell pm clear com.caniko.cenix >/dev/null || fail "clear before transport restore failed"
  "$adb" -s "$serial" logcat -c
  "$adb" -s "$serial" shell bmgr restore "$restore_token" com.caniko.cenix --monitor >"$art/backup-restore.txt" 2>&1 || fail "transport restore command failed"
  grep -q 'PACKAGE_RESTORE_FINISHED.*com.caniko.cenix' "$art/backup-restore.txt" || fail "transport restore did not finish"
  snapshot_restore_state backup-before-home 0 SYSTEM_RESTORE_PENDING
  pass "backup: agent callback stages only and leaves workspace generation unchanged"
  "$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null
  go_home
  wait_ui 'content-desc="Cenix Fixture Two, hotseat' 1
  "$adb" -s "$serial" logcat -d -s cenix >"$art/backup-logcat.txt"
  grep -q 'BACKUP_RESTORE result=staged' "$art/backup-logcat.txt" || fail "transport restore was not staged"
  grep -q 'BACKUP_RESTORE result=applied' "$art/backup-logcat.txt" || fail "transport restore was not applied"
  sleep 2
  snapshot_restore_state backup-after-home positive none
  go_home
  pass "backup: clean-data restore stages and transactionally applies workspace state"

  reboot_emulator
  go_home
  wait_ui 'content-desc="Cenix Fixture Two, hotseat' 1
  wait_ui 'content-desc="Page 1 of 2"' 1
  pass "backup: restored workspace and HOME role survive reboot"

  open_launcher_settings
  "$adb" -s "$serial" shell input swipe 160 560 160 180 400
  tap_pattern 'resource-id="com.caniko.cenix:id/exportBackup"'
  for _ in $(seq 1 20); do
    ui="$(dump_any_ui)"
    grep -q 'resource-id="android:id/button1".*text="SAVE"\|text="SAVE".*resource-id="android:id/button1"' <<<"$ui" && break
    sleep 1
  done
  grep -q 'resource-id="android:id/button1".*text="SAVE"\|text="SAVE".*resource-id="android:id/button1"' <<<"$ui" || fail "SAF export did not open the save surface"
  tap_any_pattern 'resource-id="android:id/button1"'
  wait_resumed 'com.caniko.cenix/.LauncherSettingsActivity'
  wait_ui 'text="Launcher backup exported"' 1
  "$adb" -s "$serial" pull /sdcard/Download/cenix-backup.json "$art/cenix-backup.json" >/dev/null || fail "SAF backup pull failed"
  [[ -s "$art/cenix-backup.json" ]] || fail "SAF export was empty"
  "$adb" -s "$serial" shell rm -f /sdcard/Download/cenix-backup.json
  tap_pattern 'resource-id="com.caniko.cenix:id/exportBackup"'
  for _ in $(seq 1 20); do
    ui="$(dump_any_ui)"
    grep -q 'resource-id="android:id/button1".*text="SAVE"\|text="SAVE".*resource-id="android:id/button1"' <<<"$ui" && break
    sleep 1
  done
  grep -q 'resource-id="android:id/button1".*text="SAVE"\|text="SAVE".*resource-id="android:id/button1"' <<<"$ui" || fail "second SAF export did not open the save surface"
  tap_any_pattern 'resource-id="android:id/button1"'
  wait_resumed 'com.caniko.cenix/.LauncherSettingsActivity'
  wait_ui 'text="Launcher backup exported"' 1
  "$adb" -s "$serial" pull /sdcard/Download/cenix-backup.json "$art/cenix-backup-repeat.json" >/dev/null || fail "second SAF backup pull failed"
  cmp -s "$art/cenix-backup.json" "$art/cenix-backup-repeat.json" || fail "repeat SAF exports were not byte-identical"
  sha256sum "$art/cenix-backup.json" "$art/cenix-backup-repeat.json" >"$art/backup-artifacts.sha256"
  python3 - "$art/cenix-backup.json" >"$art/backup-counts.txt" <<'PY' || fail "SAF backup was not representative"
import json, sys
document = json.load(open(sys.argv[1], encoding="utf-8"))
payload = document["payload"]
workspace = payload["workspace"]
items = workspace["items"]
kinds = [item["payload"]["kind"] for item in items]
containers = [item["container"]["kind"] for item in items]
counts = {
    "pages": len(workspace["pages"]),
    "items": len(items),
    "folders": len(workspace["folders"]),
    "folderMembers": sum(len(folder["members"]) for folder in workspace["folders"]),
    "widgets": len(payload["widgets"]),
}
print("\n".join(f"{key}={value}" for key, value in counts.items()))
assert counts["pages"] >= 2
assert counts["folders"] >= 1 and counts["folderMembers"] >= 2
assert "shortcut" in kinds and "widget" in kinds and "hotseat" in containers
assert counts["widgets"] >= 1
assert payload["settings"]["autoAddApps"] is False
PY
  pass "backup: repeat canonical exports are byte-identical and representative"

  target_avd="${avd}-restore"
  echo no | "$avdmanager" create avd -f -n "$target_avd" -k "$image_pkg" >/dev/null
  target_created_avd=1
  "$emulator_bin" -avd "$target_avd" -port $((emu_port + 2)) -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect >"$art/target-emulator.log" 2>&1 &
  target_started=$!
  for _ in $(seq 1 90); do
    target_serial="$(avd_serial "$target_avd" || true)"
    [[ -n "$target_serial" ]] && break
    sleep 2
  done
  [[ -n "$target_serial" ]] || fail "target AVD never appeared"
  for _ in $(seq 1 60); do
    [[ "$("$adb" -s "$target_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
    sleep 5
  done
  [[ "$("$adb" -s "$target_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] || fail "target AVD did not boot"
  source_serial="$serial"
  {
    echo "source_avd=$avd"
    echo "source_serial=$source_serial"
    echo "target_avd=$target_avd"
    echo "target_serial=$target_serial"
    echo "profile_mapping=personal:personal"
  } >>"$art/metadata.txt"
  serial="$target_serial"
  configure_device
  "$adb" -s "$target_serial" install -r -t "$apk" >/dev/null
  "$adb" -s "$target_serial" push "$art/cenix-backup.json" /sdcard/Download/cenix-backup.json >/dev/null
  "$adb" -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.caniko.cenix >/dev/null
  go_home
  open_launcher_settings
  tap_pattern 'resource-id="com.caniko.cenix:id/autoAddApps"'
  ui="$(dump_ui)"
  grep -q 'resource-id="com.caniko.cenix:id/autoAddApps"[^>]*checked="false"' <<<"$ui" || fail "target automatic placement preference did not disable"
  go_home
  wait_ui 'content-desc="Cenix Fixture, page 1' 0
  open_launcher_settings
  "$adb" -s "$serial" shell input swipe 160 560 160 180 400
  tap_pattern 'resource-id="com.caniko.cenix:id/importBackup"'
  tap_any_pattern 'content-desc="Show roots"'
  tap_any_pattern 'text="Downloads"'
  for _ in $(seq 1 20); do
    ui="$(dump_any_ui)"
    grep -q 'text="cenix-backup.json"' <<<"$ui" && break
    sleep 1
  done
  grep -q 'text="cenix-backup.json"' <<<"$ui" || fail "SAF import did not show the transferred backup"
  tap_any_pattern 'text="cenix-backup.json"'
  wait_ui 'text="Replace the current Home screen with' 1
  tap_pattern 'resource-id="android:id/button1"'
  wait_ui 'text="Launcher backup imported"' 1
  go_home
  wait_ui 'content-desc="com.caniko.cenix.fixture, Temporarily unavailable' 1
  wait_ui 'text="Shortcut unavailable"' 1
  wait_ui 'text="Widget unavailable"' 1
  wait_ui 'content-desc="Page 1 of 2"' 1
  pass "backup: missing applications, shortcuts, and widgets import as placeholders"
  "$adb" -s "$serial" install -r -t "$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk" >/dev/null
  wait_ui 'content-desc="Cenix Fixture Two, hotseat' 1
  wait_ui 'content-desc="Manifest action, page 1' 1
  wait_ui 'text="Widget unavailable"' 1
  tap_pattern 'text="REBIND WIDGET"'
  accept_widget_bind
  wait_ui 'text="Widget update 0"' 1
  pass "backup: package callbacks resolve app and shortcut identities; widget rebind is explicit"
  reboot_emulator
  go_home
  wait_ui 'content-desc="Cenix Fixture Two, hotseat' 1
  wait_ui 'content-desc="Manifest action, page 1' 1
  wait_ui 'text="Widget update 0"' 1
  wait_ui 'content-desc="Page 1 of 2"' 1
  pass "backup: SAF export imports on a fresh AVD and survives reboot"
  serial="$source_serial"
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
ui="$(dump_any_ui)"
echo "$ui" | grep -q 'Remove all local pages' || fail "emergency reset confirmation did not open"
tap_any_pattern 'resource-id="android:id/button1"'
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

printf 'outcome=passed\nduration_seconds=%s\n' "$(( $(date +%s) - started_at ))" >>"$art/metadata.txt"

echo "emulator conformance suite $suite passed on $serial (Android API 35 $image_kind x86_64, not GrapheneOS/mustang)"
