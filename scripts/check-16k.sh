#!/usr/bin/env bash
set -euo pipefail
fail=0
for so in "$@"; do
  if [[ ! -f "$so" ]]; then
    echo "missing $so" >&2
    fail=1
    continue
  fi
  if ! readelf -lW "$so" | awk '
    /LOAD/ {
      align = $NF
      if (align ~ /^0x/) align = strtonum(align)
      else align = align + 0
      if (align < 16384) bad = 1
      else ok = 1
    }
    END { exit (ok && !bad) ? 0 : 1 }
  '; then
    echo "LOAD align < 16KiB: $so" >&2
    readelf -lW "$so" || true
    fail=1
  fi
done
if [[ $fail -ne 0 ]]; then
  exit 1
fi
echo "16KiB LOAD align ok"
