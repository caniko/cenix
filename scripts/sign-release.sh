#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
input="${1:-$root/android/app/build/outputs/apk/release/app-release-unsigned.apk}"
output="${2:-$root/dist/cenix-release-signed.apk}"
manifest="${3:-$root/dist/signed-artifact.json}"

for name in CENIX_UNSIGNED_SHA256 CENIX_KEYSTORE CENIX_KEY_ALIAS CENIX_KEYSTORE_PASS_FILE; do
  [[ -n "${!name:-}" ]] || { echo "$name is required" >&2; exit 1; }
done
[[ -f "$input" ]] || { echo "unsigned APK not found: $input" >&2; exit 1; }
for path in "$CENIX_KEYSTORE" "$CENIX_KEYSTORE_PASS_FILE" ${CENIX_KEY_PASS_FILE:+"$CENIX_KEY_PASS_FILE"}; do
  real="$(realpath "$path")"
  [[ "$real" != "$root"/* && "$real" != /nix/store/* ]] || {
    echo "signing material must be outside Git and the Nix store" >&2
    exit 1
  }
done

actual="$(sha256sum "$input" | cut -d' ' -f1)"
[[ "$actual" == "$CENIX_UNSIGNED_SHA256" ]] || {
  echo "unsigned APK digest mismatch: expected $CENIX_UNSIGNED_SHA256, got $actual" >&2
  exit 1
}
build_tools="$sdk/build-tools/35.0.0"
zipalign="$build_tools/zipalign"
apksigner="$build_tools/apksigner"
[[ -x "$zipalign" && -x "$apksigner" ]] || { echo "Android build-tools 35.0.0 are required" >&2; exit 1; }
if "$apksigner" verify "$input" >/dev/null 2>&1; then
  echo "input must be unsigned" >&2
  exit 1
fi

mkdir -p "$(dirname "$output")" "$(dirname "$manifest")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
"$zipalign" -f -p 4 "$input" "$tmp/aligned.apk"
args=(sign --ks "$CENIX_KEYSTORE" --ks-key-alias "$CENIX_KEY_ALIAS" --ks-pass "file:$CENIX_KEYSTORE_PASS_FILE")
if [[ -n "${CENIX_KEY_PASS_FILE:-}" ]]; then
  args+=(--key-pass "file:$CENIX_KEY_PASS_FILE")
fi
"$apksigner" "${args[@]}" --out "$output" "$tmp/aligned.apk"
"$apksigner" verify --verbose --print-certs "$output" >"$tmp/verify.txt"
"$zipalign" -c -p 4 "$output"
signed="$(sha256sum "$output" | cut -d' ' -f1)"
cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$tmp/verify.txt" | head -n1)"
commit="$(git -C "$root" rev-parse HEAD)"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$root" show -s --format=%ct HEAD)}" \
  python3 - "$manifest" "$input" "$output" "$actual" "$signed" "$cert" "$commit" <<'PY'
import json, os, pathlib, sys
manifest, source, output, source_hash, output_hash, cert, commit = sys.argv[1:]
data = {
    "schemaVersion": 1,
    "sourceDateEpoch": int(os.environ["SOURCE_DATE_EPOCH"]),
    "commit": commit,
    "unsigned": {"file": pathlib.Path(source).name, "sha256": source_hash},
    "signed": {"file": pathlib.Path(output).name, "sha256": output_hash},
    "signerCertificateSha256": cert,
}
pathlib.Path(manifest).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
PY
echo "signed APK: $output"
echo "signed manifest: $manifest"
