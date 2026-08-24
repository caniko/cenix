#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dist="$root/dist"
seed="${CENIX_GRADLE_SEED_HOME:-${GRADLE_USER_HOME:-$HOME/.gradle}}"
[[ -d "$seed/caches/modules-2" && -d "$seed/wrapper" ]] || {
  echo "materialized Gradle seed cache required: $seed" >&2
  exit 1
}
git -C "$root" diff --quiet && git -C "$root" diff --cached --quiet || {
  echo "reproducibility requires a clean committed source tree" >&2
  exit 1
}
commit="$(git -C "$root" rev-parse HEAD)"
epoch="$(git -C "$root" show -s --format=%ct HEAD)"
rm -rf "$dist/repro-a" "$dist/repro-b"
mkdir -p "$dist/repro-a" "$dist/repro-b"

build() {
  local name="$1" base src gradle
  base="$dist/repro-$name"
  src="$base/src"
  gradle="$base/gradle-home"
  mkdir -p "$src" "$gradle" "$base/cargo-target" "$base/tmp" "$base/gradle-project"
  git -C "$root" archive --format=tar HEAD | tar -xf - -C "$src"
  cp -a --reflink=auto "$seed/caches" "$seed/wrapper" "$gradle/"
  rm -rf "$gradle/caches/build-cache-"* "$gradle/daemon" "$gradle/native" "$gradle/workers"
  env SOURCE_DATE_EPOCH="$epoch" CENIX_GIT_COMMIT="$commit" \
    GRADLE_USER_HOME="$gradle" CARGO_TARGET_DIR="$base/cargo-target" TMPDIR="$base/tmp" \
    "$src/scripts/build-native.sh"
  env SOURCE_DATE_EPOCH="$epoch" CENIX_GIT_COMMIT="$commit" \
    GRADLE_USER_HOME="$gradle" CARGO_TARGET_DIR="$base/cargo-target" TMPDIR="$base/tmp" \
    "$src/android/gradlew" -p "$src/android" --offline --no-daemon \
    --project-cache-dir "$base/gradle-project" :app:assembleRelease
  cp "$src/android/app/build/outputs/apk/release/app-release-unsigned.apk" "$base/app-release-unsigned.apk"
}

build a
build b
a="$dist/repro-a/app-release-unsigned.apk"
b="$dist/repro-b/app-release-unsigned.apk"
ha="$(sha256sum "$a" | cut -d' ' -f1)"
hb="$(sha256sum "$b" | cut -d' ' -f1)"
status="mismatch"
[[ "$ha" == "$hb" ]] && status="identical"
python3 - "$dist/reproducibility.json" "$commit" "$epoch" "$ha" "$hb" "$status" <<'PY'
import json, pathlib, sys
out, commit, epoch, a, b, status = sys.argv[1:]
pathlib.Path(out).write_text(json.dumps({
    "schemaVersion": 1, "commit": commit, "sourceDateEpoch": int(epoch),
    "buildA": {"sha256": a}, "buildB": {"sha256": b}, "outcome": status,
}, indent=2, sort_keys=True) + "\n")
PY
{
  echo "commit=$commit"
  echo "source_date_epoch=$epoch"
  echo "a=$ha"
  echo "b=$hb"
  echo "outcome=$status"
} >"$dist/reproducibility.txt"

if [[ "$status" == "identical" ]]; then
  echo "unsigned release APKs are byte-identical: $ha"
  exit 0
fi

python3 - "$a" "$b" "$dist/reproducibility.txt" <<'PY'
import hashlib, pathlib, sys, zipfile
def listing(path):
    with zipfile.ZipFile(path) as archive:
        return {i.filename: (hashlib.sha256(archive.read(i)).hexdigest(), i.date_time, i.compress_type)
                for i in archive.infolist()}
a, b = listing(sys.argv[1]), listing(sys.argv[2])
lines = ["differing_entries:"]
for name in sorted(set(a) | set(b)):
    if a.get(name) != b.get(name): lines.append(f"{name}: {a.get(name)} != {b.get(name)}")
with pathlib.Path(sys.argv[3]).open("a") as out: out.write("\n".join(lines) + "\n")
print("\n".join(lines[:50]))
PY
if command -v diffoscope >/dev/null; then
  diffoscope --text "$dist/reproducibility.diffoscope.txt" "$a" "$b" || true
fi
echo "unsigned release APKs differ; see dist/reproducibility.txt" >&2
exit 1
