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

- Typed UniFFI workspace reducer + filter (`API`, host tests)
- Room-only durable state, no main-thread queries on device (`API`)
- Crash-loop 3/60s process-local; user/native persist emergency (`API`, host tests)
- Diagnostics: redacted events, SAF export, 64KiB×4 ring (`API`)
- Isolated AVD name `cenix-ci-$RUN_ID`; refuse shared `cenix-api35` unless `CENIX_ALLOW_SHARED_AVD=1`
- GrapheneOS runner: `scripts/grapheneos-device-conformance.sh` (serial + `mustang` check only)

## Not claimed

- `GOS_DEV` — no mustang attached
- FOSS `default`/`aosp_atd` system image — flake still ships `google_apis` until harbor-android provides one
- Byte-identical release APKs — `scripts/compare-release-apks.sh` records hashes
- Macrobenchmark / jank — static gates only (`scripts/check-performance-static.sh`)
