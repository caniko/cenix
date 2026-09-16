#!/usr/bin/env bash
# Launcher-only first-pass smoke test on a physical GrapheneOS device.
#
# Scope contract (enforced below, not just documented):
# - Operates ONLY on the device serial in CENIX_DEVICE_SERIAL and installs
#   ONLY into the numeric test profile in CENIX_TEST_USER (never owner user 0).
# - Aborts unless the test profile exists and is currently foreground,
#   rechecked immediately before installation.
# - Refuses when com.caniko.cenix is installed for any other user, because
#   Android shares package code across users. There is no override: uninstall
#   it from every other profile first.
# - Verifies the APK really is com.caniko.cenix, contains the freshly built
#   native library, and that the library postdates all Rust sources.
# - NEVER: reboot, create/remove/switch users, change global settings, install
#   fixture apps, set the HOME role, clear data, touch other packages, or
#   uninstall anything outside the test profile.
# - HOME selection stays a manual operator step in the test profile UI; the
#   stock launcher remains installed throughout.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ADB:-adb}"
aapt="${AAPT:-aapt}"
apksigner="${APKSIGNER:-}"
serial="${CENIX_DEVICE_SERIAL:-}"
user="${CENIX_TEST_USER:-}"
expected_build="${CENIX_EXPECTED_BUILD:-}"
apk="${CENIX_APK:-$root/android/app/build/outputs/apk/debug/app-debug.apk}"
jnilibs="${CENIX_JNILIBS_DIR:-$root/android/app/src/main/jniLibs}"
evidence="${CENIX_EVIDENCE_DIR:-$root/dist/device-smoke}"
pkg="com.caniko.cenix"

# A local device definition (see harbor-android mkAndroidDeviceTools) supplies
# the serial and verifies USB transport plus product on every run. An explicit
# CENIX_DEVICE_SERIAL must agree with it when both are given.
if [[ -n "${CENIX_DEVICE_DEF:-}" ]]; then
  command -v android-device >/dev/null || {
    echo "refusing: android-device helper not on PATH (enter the Android dev shell)" >&2
    exit 1
  }
  helper_serial="$(android-device verify --definition "$CENIX_DEVICE_DEF" --adb "$adb")" || exit 1
  if [[ -n "$serial" && "$serial" != "$helper_serial" ]]; then
    echo "refusing: CENIX_DEVICE_SERIAL $serial does not match device definition $helper_serial" >&2
    exit 1
  fi
  serial="$helper_serial"
fi
[[ -n "$serial" ]] || { echo "refusing: set CENIX_DEVICE_SERIAL (or CENIX_DEVICE_DEF)" >&2; exit 1; }
[[ "$user" =~ ^[1-9][0-9]*$ ]] || { echo "refusing: set CENIX_TEST_USER to the numeric test profile id (never 0)" >&2; exit 1; }
[[ -n "$expected_build" ]] || { echo "refusing: set CENIX_EXPECTED_BUILD to the separately verified build display id" >&2; exit 1; }
[[ -f "$apk" ]] || { echo "refusing: APK not found: $apk" >&2; exit 1; }

# Local artifact checks first: never touch a device for a wrong or stale APK.
command -v "$aapt" >/dev/null || { echo "refusing: aapt not found (set AAPT)" >&2; exit 1; }
# No `head` in the pipeline: under pipefail a closed pipe can fail the whole
# check even when aapt succeeded. Read everything, parse the first line.
badging="$("$aapt" dump badging "$apk" 2>/dev/null || true)"
apk_pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"${badging%%$'\n'*}")"
[[ "$apk_pkg" == "$pkg" ]] || { echo "refusing: APK package is '${apk_pkg:-unknown}', expected $pkg" >&2; exit 1; }
device_so="$jnilibs/arm64-v8a/libcenix_ffi.so"
[[ -f "$device_so" ]] || { echo "refusing: missing $device_so (run scripts/build-native.sh first)" >&2; exit 1; }
apk_so_sha="$(unzip -p "$apk" lib/arm64-v8a/libcenix_ffi.so 2>/dev/null | sha256sum | cut -d' ' -f1)"
tree_so_sha="$(sha256sum "$device_so" | cut -d' ' -f1)"
[[ -n "$apk_so_sha" && "$apk_so_sha" == "$tree_so_sha" ]] || {
  echo "refusing: APK's arm64 libcenix_ffi.so differs from $device_so (rebuild the APK)" >&2
  exit 1
}
stale="$(find "$root/crates" "$root/android/app/src/main/java/com/caniko/cenix/uniffi" -type f -newer "$device_so" -print -quit)"
[[ -z "$stale" ]] || { echo "refusing: $stale is newer than the native library (rebuild it)" >&2; exit 1; }
# The APK itself must postdate all app sources and inputs: a native-only
# rebuild does not refresh Kotlin, resources, or the manifest.
src_inputs=()
for candidate in "$root/android/app/src" "$root/android/gradle" "$root/android/settings.gradle.kts" \
  "$root/android/build.gradle.kts" "$root/android/app/build.gradle.kts" "$root/android/gradle.properties" \
  "$root/crates" "$root/Cargo.toml" "$root/Cargo.lock"; do
  [[ -e "$candidate" ]] && src_inputs+=("$candidate")
done
stale_src="$(find "${src_inputs[@]}" -type f -newer "$apk" -print -quit)"
[[ -z "$stale_src" ]] || { echo "refusing: $stale_src is newer than the APK (rebuild it)" >&2; exit 1; }
# Signature integrity now, while the artifact is still local: record the
# signer certificate as installation evidence below.
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
verify_out="$("$apksigner" verify --print-certs "$apk" 2>&1)" \
  || { echo "refusing: APK signature does not verify" >&2; exit 1; }
cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$verify_out" | head -n1)"
[[ -n "$cert" ]] || { echo "refusing: no signer certificate in APK" >&2; exit 1; }

connected="$("$adb" devices | awk 'NR>1 && $2=="device"{print $1}')"
grep -qx "$serial" <<<"$connected" || { echo "refusing: serial $serial is not connected" >&2; exit 1; }

device="$("$adb" -s "$serial" shell getprop ro.product.device | tr -d '\r')"
build="$("$adb" -s "$serial" shell getprop ro.build.display.id | tr -d '\r')"
[[ "$device" == "mustang" ]] || { echo "refusing non-mustang device $device" >&2; exit 1; }
# Build matching only: ro.grapheneos.version is empty on this phone, and a
# build string alone is not OS attestation.
[[ "$build" == "$expected_build" ]] || { echo "refusing build ${build:-unknown}; expected $expected_build" >&2; exit 1; }

users_raw="$("$adb" -s "$serial" shell pm list users)" || { echo "refusing: user query failed" >&2; exit 1; }
mapfile -t user_ids < <(grep -o 'UserInfo{[0-9]*:' <<<"$users_raw" | grep -o '[0-9]*' || true)
[[ "${#user_ids[@]}" -gt 0 ]] || { echo "refusing: no users enumerated" >&2; exit 1; }
printf '%s\n' "${user_ids[@]}" | grep -qx "$user" || { echo "refusing: test profile $user does not exist" >&2; exit 1; }

foreground="$("$adb" -s "$serial" shell am get-current-user | tr -d '\r')"
[[ "$foreground" == "$user" ]] || {
  echo "refusing: foreground user is $foreground, switch to test profile $user first" >&2
  exit 1
}

# Package code is shared across users: check every user individually and fail
# closed on any query error. A suppressed failure would look like "absent".
holders=()
for id in "${user_ids[@]}"; do
  pkgs="$("$adb" -s "$serial" shell cmd package list packages --user "$id")" \
    || { echo "refusing: package query failed for user $id" >&2; exit 1; }
  if grep -qx "package:$pkg" <<<"$pkgs"; then
    holders+=("$id")
  fi
done
for holder in ${holders[@]+"${holders[@]}"}; do
  if [[ "$holder" != "$user" ]]; then
    echo "refusing: $pkg is installed for user $holder (holders: ${holders[*]});" >&2
    echo "uninstall it from every other profile first; package code is shared across users" >&2
    exit 1
  fi
done

"$root/scripts/check-native-symbols.sh" "$apk"

# Foreground may have changed during the checks above: recheck at the last
# moment, then perform the single allowed mutation.
foreground="$("$adb" -s "$serial" shell am get-current-user | tr -d '\r')"
[[ "$foreground" == "$user" ]] || {
  echo "refusing: foreground user changed to $foreground before install" >&2
  exit 1
}
"$adb" -s "$serial" install -r -t --user "$user" "$apk"

mkdir -p "$evidence"
sha256sum "$apk" | cut -d' ' -f1 >"$evidence/apk-sha256.txt"
{
  echo "serial=$serial user=$user device=$device build=$build"
  echo "apk=$apk native_so_sha256=$tree_so_sha signer_cert_sha256=$cert"
  git -C "$root" rev-parse HEAD
  git -C "$root" status --short
} >"$evidence/identity.txt"
"$adb" -s "$serial" shell dumpsys package "$pkg" --user "$user" \
  >"$evidence/package-dump.txt" 2>&1 || true

cat <<EOF
installed for test profile $user only. Evidence in $evidence.
Manual first pass (test profile UI, stock launcher retained):
1. Settings > Apps > Default apps > Home app: select Cenix (revert any time).
2. HOME/Back, drawer open, app launch, rapid search typing.
3. Create/rename/reorder/delete one category; open launcher settings.
4. Icon packs: LEAVE DISABLED on this pass (hang containment is bounded, not isolated).
5. Stop on: native-load failure, emergency banner, crash, or stall >10s.
After a failure only, capture the launcher's own log:
  $adb -s $serial shell pidof --user $user $pkg  # then: logcat --pid=<pid>
No device-wide log capture.
EOF
