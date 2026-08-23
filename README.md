# Cenix

Installable GrapheneOS-focused HOME launcher. First slice: list apps, filter them, launch them, survive process death, and keep working if native code fails.

## Build

```bash
nix develop
scripts/run-host-tests.sh
scripts/assemble-debug.sh
scripts/install-debug.sh   # optional, needs adb
```

## Layout

- `crates/cenix-core` — safe filter/rank
- `crates/cenix-jni` — JNI byte-array boundary
- `android/app` — HOME activity, Room, emergency mode
- `android/fixture` — tiny helper app for later device tests
- `tools` — Bun APK/manifest audit
- `docs` — baseline, parity, ADRs

`minSdk`/`compileSdk`/`targetSdk` are 35. GrapheneOS 17 is newer; the extra platform APIs are not required for this slice.
