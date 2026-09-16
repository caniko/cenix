#!/usr/bin/env bash
# Negative tests for scripts/device-smoke.sh using a fake adb/aapt.
# Every refusal case must exit nonzero with the EXPECTED refusal message and
# issue ZERO mutating device commands; the success case must issue exactly
# the one intended user-scoped install.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
smoke="$root/scripts/device-smoke.sh"
failures=0

new_env() {
  t="$(mktemp -d)"
  mkdir -p "$t/bin" "$t/jni/arm64-v8a"
  printf 'fake-so-v1' >"$t/jni/arm64-v8a/libcenix_ffi.so"
  "${PYTHON:-python3}" - "$t" <<'PY'
import sys, zipfile
t = sys.argv[1]
with zipfile.ZipFile(f"{t}/app.apk", "w") as z:
    z.writestr("lib/arm64-v8a/libcenix_ffi.so", "fake-so-v1")
PY
  cat >"$t/bin/adb" <<'SH'
#!/usr/bin/env bash
echo "$@" >>"$FAKE_ADB_LOG"
fail_on="${FAKE_FAIL:-}"
if [[ -n "$fail_on" && "$*" == *"$fail_on"* ]]; then exit 1; fi
case "$*" in
  "devices") printf 'List of devices attached\n%s\tdevice\n' "$FAKE_SERIAL" ;;
  *"getprop ro.product.device") echo "$FAKE_DEVICE" ;;
  *"getprop ro.build.display.id") echo "$FAKE_BUILD" ;;
  *"pm list users")
    echo "Users:"; echo "	UserInfo{0:Owner:c13} running"
    for u in $FAKE_USERS; do echo "	UserInfo{$u:Test:c10} running"; done ;;
  *"am get-current-user")
    # Optional mid-run foreground switch: first call returns FAKE_FOREGROUND,
    # later calls return FAKE_FG2.
    d="$(dirname "$FAKE_ADB_LOG")"
    n="$(cat "$d/fg_count" 2>/dev/null || echo 0)"; n=$((n+1)); echo "$n" >"$d/fg_count"
    if [[ -n "${FAKE_FG2:-}" && "$n" -ge 2 ]]; then echo "$FAKE_FG2"; else echo "$FAKE_FOREGROUND"; fi ;;
  *"cmd package list packages"*)
    q="$(grep -o '\-\-user [0-9]*' <<<"$*" | grep -o '[0-9]*')"
    for u in $FAKE_INSTALLED; do [[ "$u" == "$q" ]] && echo "package:com.caniko.cenix"; done; true ;;
  *"pidof"*) echo "1234" ;;
  *"dumpsys"*) echo "canned dumpsys" ;;
  *"install "*) echo "installed" ;;
  *) echo "unexpected adb call: $*" >&2; exit 2 ;;
esac
SH
  cat >"$t/bin/aapt" <<'SH'
#!/usr/bin/env bash
echo "package: name='$FAKE_AAPT_PACKAGE' versionCode='2' versionName='0.2.0'"
SH
  cat >"$t/bin/android-device" <<'SH'
#!/usr/bin/env bash
echo "$@" >>"$FAKE_ADB_LOG"
if [[ "$*" == *"verify"* ]]; then
  [[ -n "${FAKE_DEF:-}" && -f "$FAKE_DEF" ]] || { echo "android-device: refusing: definition not found" >&2; exit 1; }
  echo "$FAKE_HELPER_SERIAL"
else echo "unexpected helper call: $*" >&2; exit 2; fi
SH
  cat >"$t/bin/readelf" <<'SH'
#!/usr/bin/env bash
# Fake ELF audit: well-formed library with globals; debug sections only when
# FAKE_DEBUG_SECTIONS is set.
case "$*" in
  *"-Ws"*) echo "     1: 0000000000000000     0 FUNC    GLOBAL DEFAULT  UND fake_symbol" ;;
  *"-S"*)
    echo "  [ 1] .text PROGBITS  [ 2] .dynsym DYNSYM"
    [[ -n "${FAKE_DEBUG_SECTIONS:-}" ]] && echo "  [ 3] .debug_info PROGBITS" || true ;;
  *) exit 0 ;;
esac
SH
  cat >"$t/bin/apksigner" <<'SH'
#!/usr/bin/env bash
echo "Verifies"
echo "Signer #1 certificate SHA-256 digest: aa:bb:cc:dd"
SH
  chmod +x "$t/bin/adb" "$t/bin/aapt" "$t/bin/readelf" "$t/bin/apksigner" "$t/bin/android-device"
  echo "$t"
}

mutating() { grep -Eq '(^| )(install|uninstall|reboot)( |$)|set-home|pm clear|settings put|(^| )input( |$)|keyevent|am (start|switch)' "$1"; }

# run_smoke <tmpdir> <case-name> <expected-rc> <expected-message-or-empty> [extra env...]
# Asserts rc, refusal message, and (for refusals) zero mutating commands.
run_smoke() {
  local t="$1" name="$2" want="$3" msg="$4"; shift 4
  local rc=0
  env CENIX_DEVICE_SERIAL=S1 CENIX_EXPECTED_BUILD=2026091001 CENIX_APK="$t/app.apk" \
    CENIX_JNILIBS_DIR="$t/jni" CENIX_EVIDENCE_DIR="$t/ev" ADB="$t/bin/adb" \
    PATH="$t/bin:$PATH" FAKE_ADB_LOG="$t/adb.log" FAKE_SERIAL=S1 \
    FAKE_DEVICE=mustang FAKE_BUILD=2026091001 FAKE_AAPT_PACKAGE=com.caniko.cenix \
    "$@" "$smoke" >"$t/smoke.out" 2>"$t/smoke.err" || rc=$?
  if [[ "$rc" != "$want" ]]; then
    echo "FAIL $name: rc=$rc want=$want"; cat "$t/smoke.err"; failures=$((failures+1)); rm -rf "$t"; return
  fi
  if [[ -n "$msg" ]] && ! grep -qF "$msg" "$t/smoke.err"; then
    echo "FAIL $name: missing refusal message '$msg'"; cat "$t/smoke.err"; failures=$((failures+1)); rm -rf "$t"; return
  fi
  if [[ "$want" != "0" ]] && mutating "$t/adb.log" 2>/dev/null; then
    echo "FAIL $name: refusal issued mutating commands"; failures=$((failures+1)); rm -rf "$t"; return
  fi
  echo "pass $name"; rm -rf "$t"
}

t="$(new_env)"; rm -f "$t/adb.log"  # 1: missing serial (no CENIX_DEVICE_SERIAL)
run_smoke "$t" missing-serial 1 "set CENIX_DEVICE_SERIAL" CENIX_DEVICE_SERIAL=

t="$(new_env)"  # 2: owner user rejected before any device contact
run_smoke "$t" owner-user 1 "never 0" CENIX_TEST_USER=0 FAKE_USERS="10" FAKE_FOREGROUND="10" FAKE_INSTALLED=""

t="$(new_env)"  # 3: wrong foreground
run_smoke "$t" wrong-foreground 1 "switch to test profile" CENIX_TEST_USER=10 FAKE_USERS="10" FAKE_FOREGROUND="0" FAKE_INSTALLED=""

t="$(new_env)"  # 4: package installed for another user
run_smoke "$t" shared-package 1 "installed for user 11" CENIX_TEST_USER=10 FAKE_USERS="10 11" FAKE_FOREGROUND="10" FAKE_INSTALLED="10 11"

t="$(new_env)"  # 5: package query failure fails closed
run_smoke "$t" query-failure 1 "package query failed for user" CENIX_TEST_USER=10 FAKE_USERS="10" FAKE_FOREGROUND="10" FAKE_INSTALLED="" FAKE_FAIL="cmd package list packages"

t="$(new_env)"  # 6: wrong APK package, no device contact at all
run_smoke "$t" apk-identity 1 "APK package is 'com.evil.app'" CENIX_TEST_USER=10 FAKE_USERS="10" FAKE_FOREGROUND="10" FAKE_INSTALLED="" FAKE_AAPT_PACKAGE=com.evil.app

t="$(new_env)"  # 7: APK .so differs from tree .so
printf 'fake-so-v2' >"$t/jni/arm64-v8a/libcenix_ffi.so"
run_smoke "$t" stale-apk-so 1 "differs from" CENIX_TEST_USER=10 FAKE_USERS="10" FAKE_FOREGROUND="10" FAKE_INSTALLED=""

t="$(new_env)"  # 8: native lib older than sources
touch -d '2020-01-01' "$t/jni/arm64-v8a/libcenix_ffi.so"
run_smoke "$t" stale-native-lib 1 "newer than the native library" CENIX_TEST_USER=10 FAKE_USERS="10" FAKE_FOREGROUND="10" FAKE_INSTALLED=""

t="$(new_env)"  # 9: foreground changes mid-run
run_smoke "$t" foreground-switch 1 "changed to 0 before install" CENIX_TEST_USER=10 FAKE_USERS="10" FAKE_FOREGROUND="10" FAKE_FG2="0" FAKE_INSTALLED="10"

mkdef() { # tmpdir serial -> def path
  printf '{"schemaVersion":1,"adbSerial":"%s","product":"mustang","model":"Pixel 10 Pro XL"}' "$2" >"$1/def.json"
  echo "$1/def.json"
}

t="$(new_env)"  # 9b: definition supplies the serial (no CENIX_DEVICE_SERIAL)
d="$(mkdef "$t" S1)"
rc=0
env CENIX_DEVICE_DEF="$d" CENIX_TEST_USER=10 CENIX_EXPECTED_BUILD=2026091001 CENIX_APK="$t/app.apk" \
  CENIX_JNILIBS_DIR="$t/jni" CENIX_EVIDENCE_DIR="$t/ev" ADB="$t/bin/adb" \
  PATH="$t/bin:$PATH" FAKE_ADB_LOG="$t/adb.log" FAKE_SERIAL=S1 \
  FAKE_DEVICE=mustang FAKE_BUILD=2026091001 FAKE_USERS="10" FAKE_FOREGROUND="10" \
  FAKE_INSTALLED="10" FAKE_AAPT_PACKAGE=com.caniko.cenix FAKE_DEF="$d" FAKE_HELPER_SERIAL=S1 \
  "$smoke" >"$t/smoke.out" 2>"$t/smoke.err" || rc=$?
if [[ "$rc" != "0" ]]; then echo "FAIL def-success rc=$rc"; cat "$t/smoke.err"; failures=$((failures+1));
elif ! grep -q "install -r -t --user 10 $t/app.apk" "$t/adb.log"; then echo "FAIL def-success install args"; failures=$((failures+1));
else echo "pass def-success"; fi; rm -rf "$t"

t="$(new_env)"  # 9c: explicit serial disagreeing with the definition refuses
d="$(mkdef "$t" S1)"
rc=0
env CENIX_DEVICE_DEF="$d" CENIX_DEVICE_SERIAL=OTHER CENIX_TEST_USER=10 CENIX_EXPECTED_BUILD=2026091001 \
  CENIX_APK="$t/app.apk" CENIX_JNILIBS_DIR="$t/jni" CENIX_EVIDENCE_DIR="$t/ev" ADB="$t/bin/adb" \
  PATH="$t/bin:$PATH" FAKE_ADB_LOG="$t/adb.log" FAKE_SERIAL=S1 \
  FAKE_DEVICE=mustang FAKE_BUILD=2026091001 FAKE_USERS="10" FAKE_FOREGROUND="10" \
  FAKE_INSTALLED="10" FAKE_AAPT_PACKAGE=com.caniko.cenix FAKE_DEF="$d" FAKE_HELPER_SERIAL=S1 \
  "$smoke" >"$t/smoke.out" 2>"$t/smoke.err" || rc=$?
if [[ "$rc" != "1" ]]; then echo "FAIL def-mismatch rc=$rc"; failures=$((failures+1));
elif ! grep -qF "does not match device definition" "$t/smoke.err"; then echo "FAIL def-mismatch message"; failures=$((failures+1));
elif mutating "$t/adb.log" 2>/dev/null; then echo "FAIL def-mismatch mutations"; failures=$((failures+1));
else echo "pass def-mismatch"; fi; rm -rf "$t"

t="$(new_env)"  # 9d: definition without the helper on PATH refuses
d="$(mkdef "$t" S1)"
rc=0
env CENIX_DEVICE_DEF="$d" CENIX_TEST_USER=10 CENIX_EXPECTED_BUILD=2026091001 CENIX_APK="$t/app.apk" \
  CENIX_JNILIBS_DIR="$t/jni" CENIX_EVIDENCE_DIR="$t/ev" ADB="$t/bin/adb" \
  FAKE_ADB_LOG="$t/adb.log" FAKE_DEF="$d" \
  "$smoke" >"$t/smoke.out" 2>"$t/smoke.err" || rc=$?
if [[ "$rc" != "1" ]]; then echo "FAIL def-no-helper rc=$rc"; failures=$((failures+1));
elif ! grep -qF "android-device helper not on PATH" "$t/smoke.err"; then echo "FAIL def-no-helper message"; failures=$((failures+1));
else echo "pass def-no-helper"; fi; rm -rf "$t"

t="$(new_env)"  # 10: build mismatch refuses (build matching, not OS attestation)
rc=0
env CENIX_DEVICE_SERIAL=S1 CENIX_TEST_USER=10 CENIX_EXPECTED_BUILD=2026091001 CENIX_APK="$t/app.apk" \
  CENIX_JNILIBS_DIR="$t/jni" CENIX_EVIDENCE_DIR="$t/ev" ADB="$t/bin/adb" \
  PATH="$t/bin:$PATH" FAKE_ADB_LOG="$t/adb.log" FAKE_SERIAL=S1 \
  FAKE_DEVICE=mustang FAKE_BUILD=1999010101 FAKE_USERS="10" FAKE_FOREGROUND="10" \
  FAKE_INSTALLED="10" FAKE_AAPT_PACKAGE=com.caniko.cenix \
  "$smoke" >"$t/smoke.out" 2>"$t/smoke.err" || rc=$?
if [[ "$rc" != "1" ]]; then echo "FAIL build-mismatch rc=$rc"; failures=$((failures+1));
elif ! grep -qF "refusing build 1999010101; expected 2026091001" "$t/smoke.err"; then echo "FAIL build-mismatch message"; cat "$t/smoke.err"; failures=$((failures+1));
elif mutating "$t/adb.log" 2>/dev/null; then echo "FAIL build-mismatch mutations"; failures=$((failures+1));
else echo "pass build-mismatch"; fi; rm -rf "$t"

t="$(new_env)"  # 11: phone-like success (empty grapheneos prop is never queried)
rc=0
env CENIX_DEVICE_SERIAL=S1 CENIX_TEST_USER=10 CENIX_EXPECTED_BUILD=2026091001 CENIX_APK="$t/app.apk" \
  CENIX_JNILIBS_DIR="$t/jni" CENIX_EVIDENCE_DIR="$t/ev" ADB="$t/bin/adb" \
  PATH="$t/bin:$PATH" FAKE_ADB_LOG="$t/adb.log" FAKE_SERIAL=S1 \
  FAKE_DEVICE=mustang FAKE_BUILD=2026091001 FAKE_USERS="10" FAKE_FOREGROUND="10" \
  FAKE_INSTALLED="10" FAKE_AAPT_PACKAGE=com.caniko.cenix \
  "$smoke" >"$t/smoke.out" 2>"$t/smoke.err" || rc=$?
if [[ "$rc" != "0" ]]; then echo "FAIL success rc=$rc"; cat "$t/smoke.err"; failures=$((failures+1));
elif [[ "$(grep -cE '(^| )install( |$)' "$t/adb.log")" != "1" ]]; then echo "FAIL success install count"; failures=$((failures+1));
elif ! grep -q "install -r -t --user 10 $t/app.apk" "$t/adb.log"; then echo "FAIL success install args"; failures=$((failures+1));
elif [[ "$(grep -c 'am get-current-user' "$t/adb.log")" -lt 2 ]]; then echo "FAIL success foreground recheck"; failures=$((failures+1));
elif grep -q 'ro.grapheneos.version' "$t/adb.log"; then echo "FAIL success queried empty grapheneos prop"; failures=$((failures+1));
elif mutating <(grep -vE '(^| )install( |$)' "$t/adb.log"); then echo "FAIL success extra mutations"; failures=$((failures+1));
else echo "pass success"; fi; rm -rf "$t"

t="$(new_env)"  # 12: debug APK keeps DWARF without tripping the audit
cp "$t/app.apk" "$t/app-debug.apk"
rc=0
env CENIX_DEVICE_SERIAL=S1 CENIX_TEST_USER=10 CENIX_EXPECTED_BUILD=2026091001 CENIX_APK="$t/app-debug.apk" \
  CENIX_JNILIBS_DIR="$t/jni" CENIX_EVIDENCE_DIR="$t/ev" ADB="$t/bin/adb" \
  PATH="$t/bin:$PATH" FAKE_ADB_LOG="$t/adb.log" FAKE_SERIAL=S1 \
  FAKE_DEVICE=mustang FAKE_BUILD=2026091001 FAKE_USERS="10" FAKE_FOREGROUND="10" \
  FAKE_INSTALLED="10" FAKE_AAPT_PACKAGE=com.caniko.cenix FAKE_DEBUG_SECTIONS=1 \
  "$smoke" >"$t/smoke.out" 2>"$t/smoke.err" || rc=$?
if [[ "$rc" != "0" ]]; then echo "FAIL debug-sections rc=$rc"; cat "$t/smoke.err"; failures=$((failures+1));
elif ! grep -q "install -r -t --user 10 $t/app-debug.apk" "$t/adb.log"; then echo "FAIL debug-sections install args"; failures=$((failures+1));
else echo "pass debug-sections"; fi; rm -rf "$t"

t="$(new_env)"  # 13: release APK with DWARF still trips the audit
cp "$t/app.apk" "$t/app-release.apk"
rc=0
env CENIX_DEVICE_SERIAL=S1 CENIX_TEST_USER=10 CENIX_EXPECTED_BUILD=2026091001 CENIX_APK="$t/app-release.apk" \
  CENIX_JNILIBS_DIR="$t/jni" CENIX_EVIDENCE_DIR="$t/ev" ADB="$t/bin/adb" \
  PATH="$t/bin:$PATH" FAKE_ADB_LOG="$t/adb.log" FAKE_SERIAL=S1 \
  FAKE_DEVICE=mustang FAKE_BUILD=2026091001 FAKE_USERS="10" FAKE_FOREGROUND="10" \
  FAKE_INSTALLED="10" FAKE_AAPT_PACKAGE=com.caniko.cenix FAKE_DEBUG_SECTIONS=1 \
  "$smoke" >"$t/smoke.out" 2>"$t/smoke.err" || rc=$?
if [[ "$rc" != "1" ]]; then echo "FAIL release-sections rc=$rc"; failures=$((failures+1));
elif ! grep -qF "debug sections remain" "$t/smoke.err"; then echo "FAIL release-sections message"; cat "$t/smoke.err"; failures=$((failures+1));
elif mutating "$t/adb.log" 2>/dev/null; then echo "FAIL release-sections mutations"; failures=$((failures+1));
else echo "pass release-sections"; fi; rm -rf "$t"

if [[ "$failures" -gt 0 ]]; then echo "$failures harness failures"; exit 1; fi
echo "device-smoke harness passed"
