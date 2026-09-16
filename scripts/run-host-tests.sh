#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
cargo test --manifest-path "$root/Cargo.toml" --workspace --locked --offline
# Unit tests load the host cdylib over JNA; build it before running them.
cargo build --manifest-path "$root/Cargo.toml" -p cenix-ffi --locked --offline
"$root/scripts/check-bindings.sh"
"$root/scripts/check-foss.sh"
"$root/scripts/check-licenses.sh"
"$root/scripts/check-performance-static.sh"
# Verification must fail on stale locks, never rewrite them; lock updates are a
# deliberate separate step. check-gradle-dependencies.sh owns lock verification.
(cd "$root/android" && ./gradlew --offline :app:testDebugUnitTest)
echo "host tests passed"
