#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
runs="${CENIX_REPEAT:-5}"
base="${CENIX_REPEAT_DIR:-/tmp/cenix-repeat-$$}"
mkdir -p "$base"
echo "repeat conformance: $runs runs under $base"
for i in $(seq 1 "$runs"); do
  start=$(date +%s)
  art="$base/run-$i"
  mkdir -p "$art"
  echo "=== run $i/$runs ==="
  CENIX_RUN_ID="repeat-$i-$$" CENIX_ARTIFACTS="$art" \
    "$root/scripts/emulator-conformance.sh"
  end=$(date +%s)
  echo "run $i duration $((end - start))s" | tee -a "$base/durations.txt"
done
echo "repeat conformance passed ($runs/$runs)"
