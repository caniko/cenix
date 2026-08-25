# Vertical slice conformance

Cenix is an installable HOME app with separate full-screen HOME and All Apps surfaces, a generation-checked Room workspace, typed Rust reducer, dynamic pages, hotseat, internal drag, local application search, process-death recovery, and Kotlin emergency mode.

Device target remains Pixel 10 Pro XL (`mustang`) / GrapheneOS `2026081300`. The AOSP API 35 `default` x86_64 emulator is not that device.

## Proven on host

- Rust `cenix-core` ranking/bounds tests
- UniFFI Kotlin bindings match generated output and have no trailing whitespace
- Room persists crash-loop / emergency across a new `CrashLoopGuard`
- Emergency path never constructs `NativeAppFilter` or generated UniFFI types
- Native probe failure (`filterFactory` throw or probe throw) enters persisted emergency
- Rust command sequences preserve generation, no-overlap, deterministic ordering, page trimming, grid, and reorder invariants
- Room rejects stale generations, verifies no-op equality, rolls back failed writes, and preserves v2 screen 0, screen 1, hotseat, profiles, and cells
- Debug APK audit: HOME exported, no `INTERNET`, no `QUERY_ALL_PACKAGES`, no WebView, `libcenix_ffi.so` present

## Proven on emulator

Run only from `nix develop .#emulator-aosp`. `nix develop` (default) does not fetch the ~1.6 GiB system image.

```bash
nix develop .#emulator-aosp --command scripts/emulator-conformance.sh
```

That script:

1. Creates isolated AVD `cenix-ci-$RUN_ID` (320×640 mdpi AOSP API 35; refuses shared `cenix-api35`)
2. Installs the debug APK and takes the HOME role
3. Checks normal HOME contains the workspace and hotseat but not All Apps or emergency controls
4. Checks swipe, Back, HOME intent, search, and IME transitions, then installs, launches, and removes `com.caniko.cenix.fixture`
5. Drags Settings from All Apps into a logical workspace cell through the internal drag layer
6. Moves the item, edge-creates page two, returns it, and verifies empty trailing-page removal
7. Drags workspace to hotseat and back
8. Force-stop + HOME stays healthy; landscape/portrait retain the workspace and pinned item
9. Sends `FORCE_NATIVE_FAILURE`, checks persistence, retry, and reset isolation
10. Installs a `-PomitNative` APK and checks emergency HOME search and launch

Final P1.5 evidence used clean commit `146456280e73498c1dc74bc7bb8ca1f00346ef0c`, AOSP API 35 default x86_64 image revision 2, and isolated AVDs.

| Run | Serial | Duration | Debug APK SHA-256 | Evidence |
| --- | --- | ---: | --- | --- |
| 1 | `emulator-5578` | 335s | `1b44e49cd43fd1384402d474a5f1bb5e63a46ec51336195adca109fdd0d4bf1e` | `AOSP_EMU` |
| 2 | `emulator-5586` | 342s | `1b44e49cd43fd1384402d474a5f1bb5e63a46ec51336195adca109fdd0d4bf1e` | `AOSP_EMU` |
| 3 | `emulator-5590` | 337s | `1b44e49cd43fd1384402d474a5f1bb5e63a46ec51336195adca109fdd0d4bf1e` | `AOSP_EMU` |
| 4 | `emulator-5608` | 338s | `1b44e49cd43fd1384402d474a5f1bb5e63a46ec51336195adca109fdd0d4bf1e` | `AOSP_EMU` |
| 5 | `emulator-5588` | 336s | `1b44e49cd43fd1384402d474a5f1bb5e63a46ec51336195adca109fdd0d4bf1e` | `AOSP_EMU` |

The same commit and APK also passed full runs with forced RTL (`emulator-5620`) and 1.3x font scale (`emulator-5622`). Artifacts are under `/tmp/cenix-p15-final/{repeat,rtl,large-font}`.

Final P1 evidence used clean commit `9fb79fbc505685a1566fcbd6f6c6d6a3d3769b42`, AOSP API 35 default x86_64 image revision 2, and explicit emulator ports.

| Run | AVD | Serial | Duration | Debug APK SHA-256 | Evidence |
| --- | --- | --- | ---: | --- | --- |
| 1 | `cenix-ci-p1-final-1` | `emulator-5600` | 342s | `582994b0017b4ec4ba73497fc3f9a6f8bc60a61866c1c9f5211925004e95bc25` | `AOSP_EMU` |
| 2 | `cenix-ci-p1-final-2` | `emulator-5602` | 274s | `109288a90a292c9c4a5e3f511ed8ba3fcc77dd8f40a58bbcf3ebd21f765fea0c` | `AOSP_EMU` |
| 3 | `cenix-ci-p1-final-3` | `emulator-5604` | 271s | `109288a90a292c9c4a5e3f511ed8ba3fcc77dd8f40a58bbcf3ebd21f765fea0c` | `AOSP_EMU` |
| 4 | `cenix-ci-p1-final-4` | `emulator-5606` | 321s | `109288a90a292c9c4a5e3f511ed8ba3fcc77dd8f40a58bbcf3ebd21f765fea0c` | `AOSP_EMU` |
| 5 | `cenix-ci-p1-final-5` | `emulator-5608` | 258s | `109288a90a292c9c4a5e3f511ed8ba3fcc77dd8f40a58bbcf3ebd21f765fea0c` | `AOSP_EMU` |

P1 artifacts are under `/tmp/cenix-p1-final/run-{1..5}`. AOSP emulator evidence is never `GOS_DEV`.

## Host command set

```bash
nix develop --command scripts/check-bindings.sh
nix develop --command scripts/run-host-tests.sh
nix develop --command scripts/assemble-debug.sh
nix develop --command scripts/audit-apk.sh
nix develop --command scripts/check-16k.sh android/app/src/main/jniLibs/arm64-v8a/libcenix_ffi.so android/app/src/main/jniLibs/x86_64/libcenix_ffi.so
```
