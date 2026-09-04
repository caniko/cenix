#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
exec nix develop .#emulator-aosp --command \
  fragpipe android-with --slot nomad-aosp35-0 --slot nomad-aosp35-1 -- \
  bash -c "
    export CENIX_SERIAL=\"\${FRAGPIPE_ANDROID_SERIAL_0:?fragpipe did not export FRAGPIPE_ANDROID_SERIAL_0}\"
    export CENIX_TARGET_SERIAL=\"\${FRAGPIPE_ANDROID_SERIAL_1:?fragpipe did not export FRAGPIPE_ANDROID_SERIAL_1}\"
    exec \"\$@\"
  " bash "$root/scripts/emulator-conformance.sh" "$@"
