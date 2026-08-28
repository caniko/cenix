#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
runs="${CENIX_REPEAT:-5}"
base="${CENIX_REPEAT_DIR:-/tmp/cenix-repeat-$$}"
mkdir -p "$base"
echo "repeat conformance: $runs runs under $base"
for i in $(seq 1 "$runs"); do
  start="$(date +%s)"
  art="$base/run-$i"
  emu_port=$((5554 + (i - 1) * 4))
  mkdir -p "$art"
  echo "=== run $i/$runs ==="
  outcome="failed"
  if CENIX_RUN_ID="repeat-$i-$$" CENIX_ARTIFACTS="$art" CENIX_EMU_PORT="$emu_port" \
    "$root/scripts/emulator-conformance.sh" --suite "${CENIX_SUITE:-full}" 2>&1 | tee "$art/assertions.log"; then
    outcome="passed"
  fi
  end="$(date +%s)"
  duration="$((end - start))"
  python3 - "$art/result.json" "$i" "$duration" "$outcome" "$art" <<'PY'
import json, pathlib, sys
out, run, duration, outcome, artifacts = sys.argv[1:]
metadata = {}
path = pathlib.Path(artifacts) / "metadata.txt"
if path.exists():
    metadata = dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)
sha = pathlib.Path(artifacts) / "apk.sha256"
if sha.exists(): metadata["apkSha256"] = sha.read_text().split()[0]
pathlib.Path(out).write_text(json.dumps({
    "run": int(run), "durationSeconds": int(duration), "outcome": outcome,
    "artifacts": artifacts, **metadata,
}, indent=2, sort_keys=True) + "\n")
PY
  echo "run $i duration ${duration}s outcome $outcome" | tee -a "$base/durations.txt"
  [[ "$outcome" == "passed" ]] || { echo "repeat conformance failed at run $i" >&2; exit 1; }
done
python3 - "$base" "$runs" <<'PY'
import json, pathlib, sys
base, expected = pathlib.Path(sys.argv[1]), int(sys.argv[2])
runs = [json.loads((base / f"run-{i}" / "result.json").read_text()) for i in range(1, expected + 1)]
assert len(runs) == expected and all(run["outcome"] == "passed" for run in runs)
(base / "summary.json").write_text(json.dumps({"outcome": "passed", "runs": runs}, indent=2, sort_keys=True) + "\n")
PY
echo "repeat conformance passed ($runs/$runs)"
