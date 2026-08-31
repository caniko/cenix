# Production readiness

Evidence only. Missing a key means the claim is not made.

| Key | Meaning |
| --- | --- |
| `SRC` | Launcher3 / Android source |
| `API` | public SDK 35 |
| `AOSP_EMU` | isolated AOSP emulator run of this commit |
| `GOS_DEV` | physical GrapheneOS mustang |
| `SYSTEM` | privileged / signature |

AOSP emulator is not GrapheneOS and not Pixel 10 Pro XL (`mustang`).

## In this tree

- Typed UniFFI workspace reducer and filter; no retained Rust workspace state (`API`)
- Room v7 normalized workspace, settings, application, shortcut, folder, member, widget, and pending-widget-operation tables with tested migrations through v7 (`API`)
- Custom `WorkspacePager`, `CellLayout`, `HotseatView`, visual page indicator, and internal `DragLayer` (`API`)
- Dynamic trailing-page lifecycle and deterministic occupied-cell reorder (`API`)
- Full-screen wallpaper-backed HOME and separate All Apps surfaces with swipe, Back, HOME-intent, IME, and accessibility transitions (`API`, `AOSP_EMU`)
- Typed folder creation, append, ranked reorder, rename, extraction, package reconciliation, and single-member dissolution (`API`, `AOSP_EMU`)
- Typed manifest/dynamic shortcut placement, mixed folders, incoming pin confirmation, callback reconciliation, and transient context actions (`API`, `AOSP_EMU`)
- Production app-widget discovery, bind/configure/pin/restore, real host rendering, updates, resize, duplicate instances, placeholders, and emergency isolation (`API`, `AOSP_EMU`)
- Typed work/private profile discovery, work tabs and quiet-mode preservation, correct-user apps/shortcuts/DPC-allowed widgets, permanent removal, and lock-time private identity suppression (`API`, `AOSP_EMU`)
- Non-exported classic-Views settings, finite reference-derived phone grids, atomic Rust/Room migration, HOME role request, diagnostic export, and confirmed reset (`API`, `AOSP_EMU`)
- Callback-driven transient install/update/suspend/disable/archive/unavailable presentation with profile-scoped durable placement (`API`; suspension, disablement, replacement update, and removal are `AOSP_EMU`)
- Orientation-stable phone grid with portrait/landscape, forced-RTL, and 1.3x font-scale conformance (`AOSP_EMU`)
- Room-only durable state and no main-thread queries on device (`API`)
- P1 canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `9fb79fbc505685a1566fcbd6f6c6d6a3d3769b42` (`AOSP_EMU`)
- P1.5 canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `146456280e73498c1dc74bc7bb8ca1f00346ef0c` (`AOSP_EMU`)
- P2A canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `0e0755dbe3dde4e0d651437b2d01aea28610bb99`, debug APK SHA-256 `590c5493c57e2351749093d57f67d35515d46acf1786d6e8c4a9c5d7660b6ea9` (`AOSP_EMU`)
- P2B canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `9ab9e1af37f9b90d6dc9d86b4f700a6e9e821856`, debug APK SHA-256 `4255dbd0ca86a3136f512dd84010f5f61de9e26b37da6391769938f97a0bbf26` (`AOSP_EMU`)
- Byte-identical unsigned release APK rebuilds at P2B commit, SHA-256 `1e63bc78626037d10aba3d83c8060bfbb51aebeac6f812b8a0ee62993d6e9628`
- P3 canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `735286c011cf2559bc31d5a7aaf1f277d6ef5e7c`, debug APK SHA-256 `37885e2ab618120ef75acc81772f6549b309579b88e188f328b3e2cfb0c55b90` (`AOSP_EMU`)
- P3 forced-RTL and 1.3x font-scale full suites passed at the same commit (`AOSP_EMU`)
- P3 byte-identical unsigned release APK SHA-256 `53ff39ce44a5f1dcc0ac0562130b371e0290c04cb38033bf26e87d704700f657`; its validated CycloneDX SBOM contains 326 components
- P4 canonical conformance: 5/5 isolated AOSP API 35 full runs from clean implementation commit `d0f41c640646d5c8ac83d1b43c09b2964d9c7752`, debug APK SHA-256 `f92d79b11dce320af796e29c06a67c5bd728d65b85fa2d1e4807603a80b97124` (`AOSP_EMU`)
- P4 forced-RTL and 1.3x font-scale full suites passed on test-only descendants with unchanged production code (`AOSP_EMU`)
- P4 byte-identical unsigned release APK SHA-256 `4be5b24ddf2e7077c757b134ad86304e5cae6353df0582b03e0dc80564991f1a`; its validated CycloneDX SBOM contains 326 components
- P5A canonical conformance: 5/5 isolated AOSP API 35 full runs from clean implementation commit `235a0e5fb6ec453728fbbde076074358cef1e9a3`, debug APK SHA-256 `9b0513b3f65e4d4cbbe4be3eea963599c4dac39c4dc8aa7e6f8b2175acb42aa1` (`AOSP_EMU`)
- P5A forced-RTL and 1.3x font-scale full suites passed at the same commit (`AOSP_EMU`)
- P5A byte-identical unsigned release APK SHA-256 `4edb1da171e7848d475ccdd22fceb2d36a738c2822b466811efa6dc3324313dd`; its validated CycloneDX SBOM contains 326 components
- P5C canonical conformance: 5/5 isolated AOSP API 35 full runs from clean commit `a01cc8903ba099e3f91ee6bae4f51f9a7acd67e7`, debug APK SHA-256 `d7b3afeb3881bd5f2945bd786d23a154ecd10be9c8872eb3057e9d96192cd080` (`AOSP_EMU`)
- P5C forced-RTL and 1.3x font-scale full suites passed at the same commit (`AOSP_EMU`)
- P5C local-transport clean-data restore, source reboot, fresh-AVD SAF import, and target reboot passed; external transport behavior remains unclaimed (`AOSP_EMU`)
- P5C byte-identical unsigned release APK SHA-256 `fe65d2258e15251b6fedd8a366b527ca115b04ec3a80f85f9b251024da2e8092`; its validated CycloneDX SBOM contains 326 components
- Crash-loop 3/60s process-local; user/native persist emergency (`API`, host tests)
- Diagnostics: redacted events, SAF export, 64KiB×4 ring (`API`)
- Isolated AVD name `cenix-ci-$RUN_ID`; refuse shared `cenix-api35` unless `CENIX_ALLOW_SHARED_AVD=1`
- GrapheneOS runner: `scripts/grapheneos-device-conformance.sh` (authorized serial, `mustang`, and independently supplied exact version checks only)

## Not claimed

- `GOS_DEV` — no mustang attached
- Macrobenchmark / jank — static gates only (`scripts/check-performance-static.sh`)
- TalkBack certification — semantic actions and forced RTL are covered, but no assistive-technology device pass
- Full GrapheneOS Private Space parity — only the public third-party HOME boundary is implemented
- Archived-app, temporary-volume-unavailable, and installer-progress emulator proof — no stable AOSP setup was available
