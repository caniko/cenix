#!/usr/bin/env bash
# Automatic first-pass device test on a physical GrapheneOS device.
#
# Scope contract (enforced below, not just documented):
# - Installs ONLY the app, its instrumentation APK, and the explicitly
#   allowed fixture packages, ONLY into the numeric test profile in
#   CENIX_TEST_USER (never owner user 0). Every package is conflict-checked
#   across all users first; any holder outside the test user refuses.
# - Launches HomeActivity directly via instrumentation. NEVER presses HOME,
#   changes the default launcher, reboots, creates/removes/switches users,
#   changes global settings, clears data, or touches other packages.
# - The app install itself runs through scripts/device-smoke.sh, so all of
#   its preflight, freshness, and evidence guarantees apply.
# - Result interpretation is strict: the expected test methods must finish
#   green. Crashes, disconnects, and zero-test runs are failures, never
#   silent passes.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ADB:-adb}"
aapt="${AAPT:-aapt}"
apksigner="${APKSIGNER:-}"
serial="${CENIX_DEVICE_SERIAL:-}"
user="${CENIX_TEST_USER:-}"
expected_build="${CENIX_EXPECTED_BUILD:-}"
app_apk="${CENIX_APK:-$root/android/app/build/outputs/apk/debug/app-debug.apk}"
test_apk="${CENIX_TEST_APK:-$root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
fixture_apks="${CENIX_FIXTURE_APKS:-$root/android/fixture/build/outputs/apk/debug/fixture-debug.apk}"
evidence="${CENIX_EVIDENCE_DIR:-$root/dist/device-test}"
pkg="com.caniko.cenix"
test_pkg="com.caniko.cenix.test"
smoke_class="com.caniko.cenix.DeviceSmokeTest"
expected_tests=3
instrument_timeout="${CENIX_INSTRUMENT_TIMEOUT:-600}"

[[ -n "$serial" || -n "${CENIX_DEVICE_DEF:-}" ]] || { echo "refusing: set CENIX_DEVICE_SERIAL (or CENIX_DEVICE_DEF)" >&2; exit 1; }
[[ "$user" =~ ^[1-9][0-9]*$ ]] || { echo "refusing: set CENIX_TEST_USER to the numeric test profile id (never 0)" >&2; exit 1; }
[[ -n "$expected_build" ]] || { echo "refusing: set CENIX_EXPECTED_BUILD to the separately verified build display id" >&2; exit 1; }
[[ -f "$app_apk" ]] || { echo "refusing: app APK not found: $app_apk" >&2; exit 1; }
[[ -f "$test_apk" ]] || { echo "refusing: test APK not found: $test_apk (build :app:assembleDebugAndroidTest)" >&2; exit 1; }
fixture_roots="${CENIX_FIXTURE_ROOTS:-$root/android/fixture/build/outputs/apk/debug}"
for fixture in $fixture_apks; do
  [[ -f "$fixture" ]] || { echo "refusing: fixture APK not found: $fixture" >&2; exit 1; }
  case "$fixture" in
    "$fixture_roots"/*.apk) ;;
    *) echo "refusing: fixture outside the fixture build outputs: $fixture" >&2; exit 1 ;;
  esac
done
command -v timeout >/dev/null || { echo "refusing: timeout(1) not found" >&2; exit 1; }
command -v "$aapt" >/dev/null || { echo "refusing: aapt not found (set AAPT)" >&2; exit 1; }
commit="$(git -C "$root" rev-parse HEAD)" || { echo "refusing: cannot determine source commit" >&2; exit 1; }

# Bundle verification first: never touch a device for a wrong bundle.
pkg_of() {
  local badging name
  badging="$("$aapt" dump badging "$1" 2>/dev/null || true)"
  name="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"${badging%%$'\n'*}")"
  printf '%s' "$name"
}
cert_of() {
  "$apksigner_bin" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n1
}
[[ "$(pkg_of "$app_apk")" == "$pkg" ]] || { echo "refusing: app APK is not $pkg" >&2; exit 1; }
[[ "$(pkg_of "$test_apk")" == "$test_pkg" ]] || { echo "refusing: test APK is not $test_pkg" >&2; exit 1; }
for fixture in $fixture_apks; do
  name="$(pkg_of "$fixture")"
  case "$name" in
    com.caniko.cenix.fixture*) ;;
    *) echo "refusing: unexpected fixture package '$name' in $fixture" >&2; exit 1 ;;
  esac
done
if [[ -z "$apksigner" ]]; then
  apksigner="$(command -v apksigner || true)"
fi
if [[ -z "$apksigner" ]]; then
  for tools in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [[ -n "$tools" ]] || continue
    newest="$(ls -d "$tools/build-tools/"*/ 2>/dev/null | sort -V | tail -n1)"
    [[ -x "${newest}apksigner" ]] && apksigner="${newest}apksigner" && break
  done
fi
[[ -n "$apksigner" && -x "$apksigner" ]] || { echo "refusing: apksigner not found (set APKSIGNER)" >&2; exit 1; }
apksigner_bin="$apksigner"
app_cert="$(cert_of "$app_apk")"
[[ -n "$app_cert" ]] || { echo "refusing: app APK signature does not verify" >&2; exit 1; }
[[ "$(cert_of "$test_apk")" == "$app_cert" ]] || { echo "refusing: test APK signer differs from app APK" >&2; exit 1; }
for fixture in $fixture_apks; do
  [[ "$(cert_of "$fixture")" == "$app_cert" ]] || { echo "refusing: fixture signer differs from app APK: $fixture" >&2; exit 1; }
done
# The test APK must target the app under test, or instrumentation runs
# against the wrong package (or nothing at all).
target="$("$aapt" dump xmltree "$test_apk" AndroidManifest.xml 2>/dev/null \
  | grep -A1 'E: instrumentation' | sed -n 's/.*targetPackage[^"]*"\([^"]*\)".*/\1/p' | head -n1)"
[[ "$target" == "$pkg" ]] || { echo "refusing: test APK targets '${target:-unknown}', expected $pkg" >&2; exit 1; }
unzip -l "$app_apk" 2>/dev/null | grep -q 'lib/arm64-v8a/libcenix_ffi.so' \
  || { echo "refusing: app APK lacks the arm64 native library" >&2; exit 1; }

# App install with full preflight and evidence via the smoke script. It
# checks the build, users, foreground, and cross-user holders for the app
# package, then installs --user only. Resolve the serial first so both
# stages address the same device.
if [[ -n "${CENIX_DEVICE_DEF:-}" ]]; then
  command -v android-device >/dev/null || {
    echo "refusing: android-device helper not on PATH (enter the Android dev shell)" >&2
    exit 1
  }
  serial="$(android-device verify --definition "$CENIX_DEVICE_DEF" --adb "$adb")" || exit 1
fi
[[ -n "$serial" ]] || { echo "refusing: set CENIX_DEVICE_SERIAL (or CENIX_DEVICE_DEF)" >&2; exit 1; }
export CENIX_DEVICE_SERIAL="$serial"
CENIX_APK="$app_apk" "$root/scripts/device-smoke.sh" >/dev/null

# Conflict-check and install the test APK plus fixtures, one package at a
# time, user-scoped only. Holdings outside the test user refuse; query
# errors fail closed.
users_raw="$("$adb" -s "$serial" shell pm list users)" || { echo "refusing: user query failed" >&2; exit 1; }
mapfile -t user_ids < <(grep -o 'UserInfo{[0-9]*:' <<<"$users_raw" | grep -o '[0-9]*' || true)
[[ "${#user_ids[@]}" -gt 0 ]] || { echo "refusing: no users enumerated" >&2; exit 1; }
check_holders() { # package -> refuse unless held only by the test user
  local package="$1" holders=() id pkgs holder
  for id in "${user_ids[@]}"; do
    pkgs="$("$adb" -s "$serial" shell cmd package list packages --user "$id")" \
      || { echo "refusing: package query failed for user $id" >&2; return 1; }
    if grep -qx "package:$package" <<<"$pkgs"; then
      holders+=("$id")
    fi
  done
  for holder in ${holders[@]+"${holders[@]}"}; do
    if [[ "$holder" != "$user" ]]; then
      echo "refusing: $package is installed for user $holder; uninstall it from every other profile first" >&2
      return 1
    fi
  done
}
for extra in "$test_apk" $fixture_apks; do
  name="$(pkg_of "$extra")"
  check_holders "$name" || exit 1
  "$adb" -s "$serial" install -r -t --user "$user" "$extra"
done

# Foreground recheck at the last moment before the test run.
foreground="$("$adb" -s "$serial" shell am get-current-user | tr -d '\r')"
[[ "$foreground" == "$user" ]] || {
  echo "refusing: foreground user is $foreground, switch to test profile $user first" >&2
  exit 1
}

mkdir -p "$evidence"
{
  echo "serial=$serial user=$user build=$expected_build commit=$commit"
  echo "app=$app_apk test=$test_apk fixtures=$fixture_apks"
  echo "signer_cert_sha256=$app_cert"
  git -C "$root" status --short
} >"$evidence/identity.txt"
sha256sum "$app_apk" "$test_apk" $fixture_apks | sed "s|$root/||" >"$evidence/apk-sha256.txt"

rc=0
timeout "$instrument_timeout" "$adb" -s "$serial" shell am instrument --user "$user" \
  -w -r -e gitCommit "$commit" -e class "$smoke_class" \
  "$test_pkg/androidx.test.runner.AndroidJUnitRunner" >"$evidence/instrument-output.txt" 2>&1 || rc=$?
if [[ "$rc" -ne 0 ]]; then
  echo "device test incomplete: instrumentation exited $rc (disconnect or crash); see $evidence/instrument-output.txt" >&2
  exit 1
fi
if grep -qE 'INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE: -1|FAILURES!!!' "$evidence/instrument-output.txt"; then
  echo "device test FAILED; see $evidence/instrument-output.txt" >&2
  exit 1
fi
ran="$(sed -n 's/^OK (\(.*\) tests\?)$/\1/p' "$evidence/instrument-output.txt" | head -n1)"
if [[ -z "$ran" ]]; then
  echo "device test incomplete: no passing summary found; see $evidence/instrument-output.txt" >&2
  exit 1
fi
if [[ "$ran" != "$expected_tests" ]]; then
  echo "device test incomplete: $ran of $expected_tests expected tests finished; see $evidence/instrument-output.txt" >&2
  exit 1
fi
echo "device test passed: $ran/$expected_tests $smoke_class methods green in user $user"
