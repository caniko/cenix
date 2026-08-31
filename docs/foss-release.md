# FOSS release

License: Apache-2.0. Docs: CC-BY-4.0. See `LICENSE`, `LICENSES/`, `REUSE.toml`.

## Gates

```bash
scripts/check-foss.sh
scripts/check-licenses.sh
scripts/generate-sbom.sh
```

No `INTERNET`, no `QUERY_ALL_PACKAGES`, no WebView, no Play services.

## F-Droid

Draft metadata: `metadata/com.caniko.cenix.yml` and `fastlane/metadata/android/en-US/`.
Build is Gradle + cargo-ndk. Canonical recipe is not submitted.

## Reproducibility

`scripts/compare-release-apks.sh` builds twice and writes `dist/reproducibility.txt`.
Byte-identical APKs are not claimed until that file says they match.

## Emulator image

`nix develop .#emulator` still uses `google_apis` x86_64. That is not a FOSS image.
`.#emulator-aosp` is attempted with `systemImageTypes = ["default"]`; if the image is missing, document it — do not call `google_apis` FOSS.
