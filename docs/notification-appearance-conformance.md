# Notification and appearance conformance

This record covers clean tested commit
`35a00e13575e79f2c54089e25473ccb249f5a659`. Product behavior was introduced
by `d49d878318e6342bf1f4f03e87263e7c6afbf5f0`; the later commits only repaired
settings-scroll checks exposed by font scale 1.3. This documentation-only
commit is not the tested commit.

## Artifacts

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `a0b874c1d8f013ba884cd948574dc3da08fd659bc85ccfa424147d2d6fd09cab` |
| Unsigned release APK | `d08ee752980122f21d7175c606351f493af16357dbf4e7e1990464d9887e9c4f` |

Two clean release builds were byte-identical. The validated CycloneDX 1.5
SBOM contains 326 components and identifies the unsigned release APK by the
digest above. Host tests, generated bindings, Gradle locks and checksums,
FOSS/advisory/license/REUSE checks, static performance checks, `cargo fmt`,
`cargo clippy`, `nix flake check`, the Nix arm64 debug build, merged-manifest
APK policy, native symbols, and 16 KiB ELF LOAD alignment passed.

## AOSP emulator matrix

Every standard run used a newly created, cold-started Android API 35 `default`
x86_64 system image revision 2 at 320 x 640 mdpi. Reused serial numbers do not
indicate reused AVD state.

| Run | Serial | Font scale | Forced RTL | Outcome |
| --- | --- | ---: | --- | --- |
| 1 | `emulator-5582` | 1.0 | no | passed |
| 2 | `emulator-5582` | 1.0 | no | passed |
| 3 | `emulator-5596` | 1.0 | no | passed |
| 4 | `emulator-5582` | 1.0 | no | passed |
| 5 | `emulator-5592` | 1.0 | no | passed |
| RTL | `emulator-5606` | 1.0 | yes | passed |
| Large font | `emulator-5588` | 1.3 | no | passed |

The complete suite exercised notification authorization, eligible and
ineligible posts, profile/package/authorization clearing, package/profile
projection, themed preference recreation, the system wallpaper action,
successful-new-install placement, duplicate rejection, private-profile
exclusion, rotation, process recreation, and all prior launcher scenarios.
All seven runs used the debug APK digest above.

## Appearance and reboot

An isolated AOSP emulator used a temporary test-only APK outside the repository
to set solid light and dark system wallpapers through public
`WallpaperManager.setBitmap`. Cenix changed the visible settings activity from
light to dark while it was alive; the dark activity resource configuration
reported `night`. The light and dark screenshots have SHA-256 digests
`1b6c0ebd3538d5b84a11cac157784862d246ca812e98d7d3ea3d6cf168bae42d`
and `04f59efefff5879890afec3eef4926b0409c002d32e28d40f6c7b0dee43e7229`.

After a real emulator reboot, Cenix retained the HOME role, themed-icons
preference, notification-dots preference, automatic-placement preference, and
selected 3x3 grid. The restored settings screen exposed the exact tested
source commit and retained the dark `night` resource configuration. The
post-reboot screenshot digest is
`1c9479574a7ca9a1721e1a77714c6b459134b7e878be9b79154a6adb6551d17b`.

Evidence is under
`/data/scratch/tmp/opencode/cenix-p5b-final-35a00e1`; appearance and reboot
artifacts are in its `appearance-reboot` directory.

## Evidence boundary

- This is `AOSP_EMU`, not `GOS_DEV`; no physical Pixel 10 Pro XL (`mustang`) was attached.
- Archive/restore, temporary-volume unavailability, and observable installer-session progress lacked stable AOSP setup and are not claimed as emulator-proven.
- No TalkBack certification or Macrobenchmark/Perfetto measured-jank result exists.
- The Nix Android path requires `--option sandbox false`.
