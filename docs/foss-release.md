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

Metadata: `metadata/com.caniko.cenix.yml` and `fastlane/metadata/android/en-US/`.
v0.2.0 targets the official repository: GitHub CI builds the release commit,
F-Droid independently rebuilds the `v0.2.0` tag (NDK 29, build-tools 35.0.0,
cargo-ndk native libs) and verifies it before publication.
The metadata remains an unvalidated draft, not a publication record. Its Rust/cargo-ndk
toolchain setup, signing identity and reproducible-binary URL must be completed before submission.
Not yet proven: run `fdroid lint` / `fdroid build` against the tag and record
the verification before claiming reproducible F-Droid publication.

## Reproducibility

`scripts/compare-release-apks.sh` builds twice and writes `dist/reproducibility.txt`.
Byte-identical APKs are not claimed until that file says they match.

GitHub `host.yml` retains host reports (including failures) and, only after successful
gates, the unsigned APK, SBOM, license report and reproducibility record. Artifact names
include the source SHA and run attempt. The persistent emulator job accepts default-branch
pushes only; it does not execute PR code. Neither job has signing secrets or write permissions.

Release evidence still required: an actual GitHub run/artifact for the release source,
F-Droid lint/build and independent rebuild verification, protected signing with the intended
certificate, and signed ARM64 GrapheneOS install/HOME/reboot/upgrade checks in both navigation
modes. Local unit tests and lint are not substitutes. Do not create the release tag or publish
the draft as a finished release before these gates are satisfied.

## Emulator image

`nix develop .#emulator` still uses `google_apis` x86_64. That is not a FOSS image.
`.#emulator-aosp` is attempted with `systemImageTypes = ["default"]`; if the image is missing, document it — do not call `google_apis` FOSS.
