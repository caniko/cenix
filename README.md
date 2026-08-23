# Cenix

Installable GrapheneOS-focused HOME launcher. First slice: list apps, filter them, launch them, survive process death, and keep working if native code fails.

Device target: Pixel 10 Pro XL (`mustang`), GrapheneOS `2026081300` (Launcher3 `17` / `e5fde8f4368539554b07ee136abd27bc7b4284c1`). This tree builds an API 35 debug APK; the AOSP `x86_64` emulator is not GrapheneOS/`mustang`.

## Build

```bash
nix develop
scripts/run-host-tests.sh
scripts/assemble-debug.sh
scripts/install-debug.sh   # optional, needs adb
nix develop .#emulator --command scripts/emulator-conformance.sh  # AOSP API 35 x86_64, not GrapheneOS/mustang
```

Impure debug APK: `nix build .#apk-debug` (`mkAndroidApk`, sandbox off).

## Layout

- `crates/cenix-core` — safe filter/rank (`#![forbid(unsafe_code)]`)
- `crates/cenix-ffi` — typed UniFFI `filter_and_order_apps`
- `android/app` — HOME activity, Room-only state, emergency mode
- `android/fixture` — helper app for package-callback smoke
- `android/app/src/main/java/com/caniko/cenix/uniffi` — committed generated Kotlin

`minSdk`/`compileSdk`/`targetSdk` are 35. Room is the only durable store. Emergency mode never imports generated UniFFI types.
