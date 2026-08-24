#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/dist/sbom.cdx.json}"
mkdir -p "$(dirname "$out")"
python3 - "$root" "$out" <<'PY'
import json, pathlib, sys, datetime
root, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
comps = []
lock = (root / "Cargo.lock").read_text()
name = ver = None
for line in lock.splitlines():
    if line.startswith("name = "):
        name = line.split("=",1)[1].strip().strip('"')
    elif line.startswith("version = ") and name:
        ver = line.split("=",1)[1].strip().strip('"')
        comps.append({"type":"library","name":name,"version":ver})
        name = ver = None
sbom = {
    "bomFormat": "CycloneDX",
    "specVersion": "1.5",
    "version": 1,
    "metadata": {
        "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "component": {"type":"application","name":"cenix","version":"0.1.0"},
    },
    "components": comps,
}
out.write_text(json.dumps(sbom, indent=2) + "\n")
print(f"wrote {out} ({len(comps)} cargo components)")
PY
