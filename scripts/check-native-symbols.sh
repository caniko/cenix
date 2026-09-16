#!/usr/bin/env bash
# Audit native libraries in an APK: valid ELF with dynamic symbols, and no
# leftover .debug_* sections. Debug builds legitimately carry DWARF (the nix
# debug cdylib and any local `cargo ndk build` without --release do), so
# callers pass --allow-debug-sections for non-release artifacts. This mirrors
# scripts/audit-apk.sh, which applies this audit to release APKs only.
set -euo pipefail
allow_debug=0
if [[ "${1:-}" == "--allow-debug-sections" ]]; then
  allow_debug=1
  shift
fi
apk="${1:?usage: check-native-symbols.sh [--allow-debug-sections] APK}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -qo "$apk" 'lib/*/*.so' -d "$tmp"
mapfile -t libraries < <(find "$tmp/lib" -type f -name '*.so' | sort)
[[ "${#libraries[@]}" -gt 0 ]] || { echo "no native libraries in $apk" >&2; exit 1; }
for library in "${libraries[@]}"; do
  readelf -h "$library" >/dev/null
  sections="$(readelf -S "$library")"
  if [[ "$allow_debug" == "0" ]] && grep -qE '\.debug_(info|line|str)' <<<"$sections"; then
    echo "debug sections remain in ${library#$tmp/}" >&2
    exit 1
  fi
  symbols="$(readelf -Ws "$library")"
  grep -q 'GLOBAL' <<<"$symbols" || { echo "no dynamic symbols in ${library#$tmp/}" >&2; exit 1; }
done
echo "native symbol audit passed: $apk"
