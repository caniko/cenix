#!/usr/bin/env bash
set -euo pipefail
apk="$1"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -qo "$apk" 'lib/*/*.so' -d "$tmp"
mapfile -t libraries < <(find "$tmp/lib" -type f -name '*.so' | sort)
[[ "${#libraries[@]}" -gt 0 ]] || { echo "no native libraries in $apk" >&2; exit 1; }
for library in "${libraries[@]}"; do
  readelf -h "$library" >/dev/null
  sections="$(readelf -S "$library")"
  if grep -qE '\.debug_(info|line|str)' <<<"$sections"; then
    echo "debug sections remain in ${library#$tmp/}" >&2
    exit 1
  fi
  symbols="$(readelf -Ws "$library")"
  grep -q 'GLOBAL' <<<"$symbols" || { echo "no dynamic symbols in ${library#$tmp/}" >&2; exit 1; }
done
echo "native symbol audit passed: $apk"
