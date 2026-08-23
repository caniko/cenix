#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
cargo test --manifest-path "$root/Cargo.toml" --workspace
(cd "$root/tools" && bun test)
(cd "$root/android" && ./gradlew :app:testDebugUnitTest)
echo "host tests passed"
