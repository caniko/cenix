#!/usr/bin/env bash
set -euo pipefail
adb="${ADB:-adb}"
want="${CENIX_GOS_SERIAL:-}"
want_os="${CENIX_GOS_VERSION:-}"
if [[ -z "$want" ]]; then
  echo "refusing: set CENIX_GOS_SERIAL to an authorized GrapheneOS serial" >&2
  exit 1
fi
if [[ -z "$want_os" ]]; then
  echo "refusing: set CENIX_GOS_VERSION to the separately verified GrapheneOS version" >&2
  exit 1
fi
if ! "$adb" devices | awk 'NR>1 && $2=="device"{print $1}' | grep -qx "$want"; then
  echo "authorized serial $want is not connected" >&2
  exit 1
fi
device="$("$adb" -s "$want" shell getprop ro.product.device | tr -d '\r')"
os="$("$adb" -s "$want" shell getprop ro.grapheneos.version | tr -d '\r')"
echo "device=$device serial=$want grapheneos=${os:-unknown}"
if [[ "$device" != "mustang" ]]; then
  echo "refusing non-mustang device $device" >&2
  exit 1
fi
if [[ "$os" != "$want_os" ]]; then
  echo "refusing GrapheneOS version ${os:-unknown}; expected $want_os" >&2
  exit 1
fi
echo "physical GrapheneOS runner contract ok; no parity run executed"
echo "manual checklist: settings entry, finite grids, shrink/expand/cold boot, widget blocker, install/update/suspend/disable/archive/unavailable, work/private isolation, RTL, font 1.3"
echo "outstanding: execute only with operator-authorized device, build identity, and evidence directory"
exit 0
