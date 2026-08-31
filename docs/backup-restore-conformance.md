# Backup and restore conformance

This record covers clean tested commit
`a01cc8903ba099e3f91ee6bae4f51f9a7acd67e7`. The documentation-only commit
containing this record is not the tested implementation commit.

## Artifacts

| Artifact | SHA-256 |
| --- | --- |
| Emulator debug APK | `d7b3afeb3881bd5f2945bd786d23a154ecd10be9c8872eb3057e9d96192cd080` |
| Nix arm64 debug APK | `b87906ddb0c475afaedc9952db6a28a23b0b61f144f83fd9c2b416d3aab165a1` |
| Unsigned release APK | `fe65d2258e15251b6fedd8a366b527ca115b04ec3a80f85f9b251024da2e8092` |

Two clean release builds were byte-identical. The validated CycloneDX 1.5
SBOM contains 326 components and identifies the unsigned release APK by the
digest above. Host tests, generated bindings, Gradle locks and checksums,
FOSS/advisory/license/REUSE checks, static performance checks, `cargo fmt`,
`cargo clippy`, `nix flake check`, the Nix arm64 debug build, merged-manifest
APK policy, native symbols, and 16 KiB ELF LOAD alignment passed.

## AOSP emulator matrix

Every run used a newly created, cold-started Android API 35 `default` x86_64
system image revision 2 at 320 x 640 mdpi. Reused serial numbers do not
indicate reused AVD state.

| Run | Serial | Duration | Outcome |
| --- | --- | ---: | --- |
| 1 | `emulator-5562` | 1679s | passed |
| 2 | `emulator-5566` | 1665s | passed |
| 3 | `emulator-5562` | 1670s | passed |
| 4 | `emulator-5566` | 1664s | passed |
| 5 | `emulator-5566` | 1663s | passed |

The same commit and debug APK passed the full suite with forced RTL
(`emulator-5562`) and at font scale 1.3 (`emulator-5582`).

All seven runs exercised the bounded personal-only Android full-backup
artifact through AOSP's local transport, clean-data staging and transactional
application, HOME-role and workspace survival across reboot, SAF export, a
fresh independently named target AVD, replace confirmation, absence before
import with automatic placement disabled, restored placement, and target
reboot persistence. Existing launcher conformance also passed.

Artifacts are under `/tmp/cenix-repeat-1538162` and
`/tmp/cenix-p5c-final-a01cc89/{rtl,font-1.3}`. Reproducibility and SBOM records
are under `dist/` in the tested worktree.

## Evidence boundary

- Local AOSP transport execution and SAF import are `AOSP_EMU`; they do not prove cloud, D2D, Seedvault, or GrapheneOS transport behavior.
- No physical Pixel 10 Pro XL (`mustang`) was attached, so no `GOS_DEV` claim is made.
- No TalkBack certification or Macrobenchmark/Perfetto measured-jank result exists.
- Archive/restore, temporary-volume unavailability, and observable installer-session progress remain unproven on the emulator.
