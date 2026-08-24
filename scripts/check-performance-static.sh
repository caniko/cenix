#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
grep -q 'android.permission.INTERNET' "$root/android/app/src/main/AndroidManifest.xml" && {
  echo "INTERNET permission present" >&2
  exit 1
}
if grep -q 'allowMainThreadQueries' "$root/android/app/src/main/java/com/caniko/cenix/db/CenixDatabase.kt" &&
   ! grep -B2 'allowMainThreadQueries' "$root/android/app/src/main/java/com/caniko/cenix/db/CenixDatabase.kt" | grep -q robolectric; then
  echo "production Room still allows main-thread queries" >&2
  exit 1
fi
if grep -R --include='*.kt' -nE 'while \(true\)|Timer\(|scheduleAtFixedRate' "$root/android/app/src/main" | grep -v '/uniffi/' | grep -q .; then
  echo "possible persistent polling" >&2
  exit 1
fi
echo "static performance gates passed"
