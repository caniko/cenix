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
- Room v5 normalized workspace, application, shortcut, folder, and member tables with tested migrations through v5 (`API`)
- Custom `WorkspacePager`, `CellLayout`, `HotseatView`, visual page indicator, and internal `DragLayer` (`API`)
- Dynamic trailing-page lifecycle and deterministic occupied-cell reorder (`API`)
- Full-screen wallpaper-backed HOME and separate All Apps surfaces with swipe, Back, HOME-intent, IME, and accessibility transitions (`API`, `AOSP_EMU`)
- Typed folder creation, append, ranked reorder, rename, extraction, package reconciliation, and single-member dissolution (`API`, `AOSP_EMU`)
- Typed manifest/dynamic shortcut placement, mixed folders, incoming pin confirmation, callback reconciliation, and transient context actions (`API`, `AOSP_EMU`)
- Orientation-stable phone grid with portrait/landscape, forced-RTL, and 1.3x font-scale conformance (`AOSP_EMU`)
- Room-only durable state and no main-thread queries on device (`API`)
- P1 canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `9fb79fbc505685a1566fcbd6f6c6d6a3d3769b42` (`AOSP_EMU`)
- P1.5 canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `146456280e73498c1dc74bc7bb8ca1f00346ef0c` (`AOSP_EMU`)
- P2A canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `0e0755dbe3dde4e0d651437b2d01aea28610bb99`, debug APK SHA-256 `590c5493c57e2351749093d57f67d35515d46acf1786d6e8c4a9c5d7660b6ea9` (`AOSP_EMU`)
- P2B canonical conformance: 5/5 isolated AOSP API 35 runs from clean commit `9ab9e1af37f9b90d6dc9d86b4f700a6e9e821856`, debug APK SHA-256 `4255dbd0ca86a3136f512dd84010f5f61de9e26b37da6391769938f97a0bbf26` (`AOSP_EMU`)
- Byte-identical unsigned release APK rebuilds at P2B commit, SHA-256 `1e63bc78626037d10aba3d83c8060bfbb51aebeac6f812b8a0ee62993d6e9628`
- Crash-loop 3/60s process-local; user/native persist emergency (`API`, host tests)
- Diagnostics: redacted events, SAF export, 64KiB×4 ring (`API`)
- Isolated AVD name `cenix-ci-$RUN_ID`; refuse shared `cenix-api35` unless `CENIX_ALLOW_SHARED_AVD=1`
- GrapheneOS runner: `scripts/grapheneos-device-conformance.sh` (serial + `mustang` check only)

## Not claimed

- `GOS_DEV` — no mustang attached
- Macrobenchmark / jank — static gates only (`scripts/check-performance-static.sh`)
- TalkBack certification — semantic actions and forced RTL are covered, but no assistive-technology device pass
