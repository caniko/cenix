#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
for file in android/app/gradle.lockfile android/fixture/gradle.lockfile android/gradle/verification-metadata.xml; do
  [[ -s "$root/$file" ]] || { echo "missing $file" >&2; exit 1; }
done
if grep -R --include='*.gradle' --include='*.gradle.kts' -nE ':[^:"]*[+]|SNAPSHOT|latest\.|git[+:].*(main|master|trunk)' "$root/android" \
  | grep -v '/build/' | grep -vE 'version\.(contains|endsWith|startsWith)' | grep -q .; then
  echo "dynamic, snapshot, or mutable Git Gradle dependency" >&2
  exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
(cd "$root/android" && ./gradlew --offline checkPinnedDependencies \
  :app:testDebugUnitTest :app:lintDebug :fixture:assembleDebug)
echo "Gradle locks, checksums, and offline resolution passed"
