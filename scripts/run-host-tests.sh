#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
cargo test --manifest-path "$root/Cargo.toml" --workspace --locked --offline
"$root/scripts/check-bindings.sh"
"$root/scripts/check-foss.sh"
"$root/scripts/check-licenses.sh"
"$root/scripts/check-performance-static.sh"
(cd "$root/android" && ./gradlew --offline --write-locks :app:testDebugUnitTest)
echo "host tests passed"
