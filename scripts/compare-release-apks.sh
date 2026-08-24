#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
a="$root/dist/repro-a"
b="$root/dist/repro-b"
rm -rf "$a" "$b"
mkdir -p "$a" "$b"
(cd "$root/android" && ./gradlew :app:assembleRelease)
cp "$root/android/app/build/outputs/apk/release/app-release.apk" "$a/app-release.apk"
(cd "$root/android" && ./gradlew clean :app:assembleRelease)
cp "$root/android/app/build/outputs/apk/release/app-release.apk" "$b/app-release.apk"
ha=$(sha256sum "$a/app-release.apk" | awk '{print $1}')
hb=$(sha256sum "$b/app-release.apk" | awk '{print $1}')
{
  echo "a=$ha"
  echo "b=$hb"
} >"$root/dist/reproducibility.txt"
if [[ "$ha" == "$hb" ]]; then
  echo "release APKs are byte-identical"
  exit 0
fi
echo "release APKs differ; listing zip entries" | tee -a "$root/dist/reproducibility.txt"
python3 - "$a/app-release.apk" "$b/app-release.apk" "$root/dist/reproducibility.txt" <<'PY'
import hashlib, sys, zipfile
from pathlib import Path
def listing(path):
    with zipfile.ZipFile(path) as z:
        return {i.filename: (i.file_size, i.CRC, i.date_time) for i in z.infolist()}
a, b = listing(sys.argv[1]), listing(sys.argv[2])
out = Path(sys.argv[3])
keys = sorted(set(a)|set(b))
lines = ["differing entries:"]
for k in keys:
    if a.get(k) != b.get(k):
        lines.append(f"{k}: {a.get(k)} vs {b.get(k)}")
out.write_text(out.read_text() + "\n".join(lines) + "\n")
print("\n".join(lines[:40]))
if len(lines) > 40:
    print(f"... {len(lines)-40} more")
PY
echo "reproducibility not claimed; see dist/reproducibility.txt"
exit 0
