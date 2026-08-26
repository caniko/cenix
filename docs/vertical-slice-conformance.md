# Vertical slice conformance

Cenix is an installable HOME app with separate full-screen HOME and All Apps surfaces, a generation-checked Room workspace, typed Rust reducer, dynamic pages, hotseat, folders, shortcuts, production app widgets, work/private profile policy, transient context actions, internal drag, local application search, process-death recovery, and Kotlin emergency mode.

Device target remains Pixel 10 Pro XL (`mustang`) / GrapheneOS `2026081300`. The AOSP API 35 `default` x86_64 emulator is not that device.

## Proven on host

- Rust `cenix-core` ranking/bounds tests
- UniFFI Kotlin bindings match generated output and have no trailing whitespace
- Room persists crash-loop / emergency across a new `CrashLoopGuard`
- Emergency path never constructs `NativeAppFilter` or generated UniFFI types
- Native probe failure (`filterFactory` throw or probe throw) enters persisted emergency
- Rust command sequences preserve generation, no-overlap, deterministic ordering, page trimming, grid, and reorder invariants
- Room rejects stale generations, verifies no-op equality, rolls back failed writes, and migrates workspace data through the normalized v5 shortcut schema
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
5. Drags the deterministic fixture from All Apps into a logical workspace cell through the internal drag layer
6. Moves the item, edge-creates page two, returns it, and verifies empty trailing-page removal
7. Drags workspace to hotseat and back
8. Force-stop + HOME stays healthy; landscape/portrait retain the workspace and pinned item
9. Exercises folder create/append/spring-open/reorder/move/reconciliation/dissolution paths
10. Discovers, launches, drags, docks, folders, updates, disables, pins, and reconciles typed shortcuts
11. Verifies app info and Android-owned uninstall confirmation, including cancellation
12. Sends `FORCE_NATIVE_FAILURE`, checks persistence, retry, and reset isolation
13. Exercises widget discovery, rendering, updates, resize, duplicate instances, move, configure, pin, removal, and emergency isolation
14. Installs a `-PomitNative` APK and checks emergency HOME search and launch
15. The `profiles` suite creates managed/private profiles, applies a minimal test DPC for work widgets, and verifies profile launch, shortcuts, widgets, quiet/unquiet, package isolation, permanent removal, and private lock leakage

Final P3 evidence used clean implementation commit `735286c011cf2559bc31d5a7aaf1f277d6ef5e7c`, AOSP API 35 `default` x86_64 image revision 2, and debug APK SHA-256 `37885e2ab618120ef75acc81772f6549b309579b88e188f328b3e2cfb0c55b90`.

| Run | Duration | Evidence |
| --- | ---: | --- |
| 1 | 1022s | `AOSP_EMU` |
| 2 | 920s | `AOSP_EMU` |
| 3 | 930s | `AOSP_EMU` |
| 4 | 945s | `AOSP_EMU` |
| 5 | 987s | `AOSP_EMU` |

The same commit passed forced RTL and font scale 1.3. Two clean unsigned release builds were byte-identical at SHA-256 `53ff39ce44a5f1dcc0ac0562130b371e0290c04cb38033bf26e87d704700f657`; the linked CycloneDX SBOM validated 326 components. See [Production widget conformance](widget-conformance.md). This is `AOSP_EMU`, never `GOS_DEV`; no physical mustang, TalkBack certification, or Macrobenchmark evidence exists. The Nix Android path still requires `--option sandbox false`.

Final P2B evidence used clean commit `9ab9e1af37f9b90d6dc9d86b4f700a6e9e821856`, AOSP API 35 default x86_64 image revision 2, and debug APK SHA-256 `4255dbd0ca86a3136f512dd84010f5f61de9e26b37da6391769938f97a0bbf26`.

| Run | Serial | Duration | Evidence |
| --- | --- | ---: | --- |
| 1 | `emulator-5606` | 743s | `AOSP_EMU` |
| 2 | `emulator-5594` | 736s | `AOSP_EMU` |
| 3 | `emulator-5598` | 737s | `AOSP_EMU` |
| 4 | `emulator-5596` | 744s | `AOSP_EMU` |
| 5 | `emulator-5584` | 783s | `AOSP_EMU` |

The same commit passed the full flow with forced RTL (`emulator-5584`) and 1.3x font scale (`emulator-5584`). Artifacts are under `/data/scratch/tmp/opencode/cenix-p2b-final/{repeat-9ab9e1a-final,rtl-9ab9e1a,large-font-9ab9e1a}`. This is `AOSP_EMU`, never `GOS_DEV`.

Final P2A evidence used clean commit `0e0755dbe3dde4e0d651437b2d01aea28610bb99`, AOSP API 35 default x86_64 image revision 2, and debug APK SHA-256 `590c5493c57e2351749093d57f67d35515d46acf1786d6e8c4a9c5d7660b6ea9`.

| Run | Serial | Duration | Evidence |
| --- | --- | ---: | --- |
| 1 | `emulator-5586` | 408s | `AOSP_EMU` |
| 2 | `emulator-5592` | 411s | `AOSP_EMU` |
| 3 | `emulator-5576` | 404s | `AOSP_EMU` |
| 4 | `emulator-5604` | 406s | `AOSP_EMU` |
| 5 | `emulator-5582` | 404s | `AOSP_EMU` |

The same commit and APK passed the full flow with measured forced RTL (`emulator-5606`) and 1.3x font scale (`emulator-5604`). Artifacts are under `/tmp/cenix-repeat-2191916`, `/tmp/cenix-conformance.wvor3x`, and `/tmp/cenix-conformance.bOrgig`. This is `AOSP_EMU`, never `GOS_DEV`.

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
nix flake check
nix build .#apk-debug-arm64 --option sandbox false
```
