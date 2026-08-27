# Settings, grid, and package conformance

This record covers clean implementation commit
`235a0e5fb6ec453728fbbde076074358cef1e9a3`. The documentation-only commit
containing this record is not the tested implementation commit.

## Artifacts

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `9b0513b3f65e4d4cbbe4be3eea963599c4dac39c4dc8aa7e6f8b2175acb42aa1` |
| Unsigned release APK | `4edb1da171e7848d475ccdd22fceb2d36a738c2822b466811efa6dc3324313dd` |

Two clean release builds were byte-identical. The validated CycloneDX 1.5
SBOM contains 326 components and identifies the unsigned release APK by the
digest above. Host tests, generated-binding comparison, FOSS, advisory,
license, REUSE, and static-performance gates passed.

## AOSP emulator matrix

Every run used an isolated, cold-started Android API 35 `default` x86_64
system image revision 2 at 320 x 640 mdpi. No saved AVD snapshot was reused.

| Run | Serial | Duration | Outcome |
| --- | --- | ---: | --- |
| 1 | `emulator-5582` | 1422s | passed |
| 2 | `emulator-5572` | 1438s | passed |
| 3 | `emulator-5576` | 1308s | passed |
| 4 | `emulator-5586` | 1353s | passed |
| 5 | `emulator-5608` | 1234s | passed |

The same implementation commit and debug APK passed the full suite with
forced RTL (`emulator-5598`) and at font scale 1.3 (`emulator-5580`). The full
suite includes portrait/landscape transitions and process recreation. It does
not include a separate post-mutation emulator reboot scenario.

The P5A additions exercised finite compatible grid discovery, build identity,
settings task restoration, confirmed reset, shrink/repeat/expand migration,
Room/Rust atomic persistence, package suspension, disablement, replacement
update, callback removal, and profile-scoped retention. Existing workspace,
folder, shortcut, widget, work/private profile, emergency, and native-absent
scenarios also passed.

Artifacts are under
`/data/scratch/tmp/opencode/cenix-p5a-final-235a0e5/{repeat,rtl,font-1.3}`;
host, reproducibility, and SBOM records are in the same parent directory.

## Evidence boundary

- This is `AOSP_EMU`, not `GOS_DEV`; no physical Pixel 10 Pro XL (`mustang`) was attached.
- Archive/restore, temporary-volume unavailability, and observable installer-session progress lacked stable AOSP setup and are not claimed as emulator-proven.
- No TalkBack certification or Macrobenchmark/Perfetto measured-jank result exists.
- The Nix Android path requires `--option sandbox false`.
