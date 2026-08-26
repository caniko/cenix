# Production widget conformance

This documentation records tests run against implementation commit
`735286c011cf2559bc31d5a7aaf1f277d6ef5e7c`. The documentation-only commit
containing this record is not the tested implementation commit.

## Artifacts

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `37885e2ab618120ef75acc81772f6549b309579b88e188f328b3e2cfb0c55b90` |
| Unsigned release APK | `53ff39ce44a5f1dcc0ac0562130b371e0290c04cb38033bf26e87d704700f657` |

Two clean release builds were byte-identical. The validated CycloneDX SBOM
contains 326 components and identifies the unsigned release APK by the digest
above.

## AOSP emulator matrix

Every run used the Android API 35 `default` x86_64 system image, revision 2.

| Run | Duration | Outcome |
| --- | ---: | --- |
| 1 | 1022s | passed |
| 2 | 920s | passed |
| 3 | 930s | passed |
| 4 | 945s | passed |
| 5 | 987s | passed |

The same implementation commit also passed a forced-RTL full run and a full
run at font scale 1.3. The full suite covered all existing launcher behavior,
including real `AppWidgetHostView` rendering, updates, collection refresh,
logical RTL resize, duplicate provider instances, move, configuration
cancellation and acceptance, incoming pin cancellation and acceptance,
provider removal, emergency isolation, and reset.

## Evidence boundary

- This is `AOSP_EMU`, not `GOS_DEV`.
- No physical Pixel 10 Pro XL (`mustang`) was tested.
- No TalkBack certification exists.
- No Macrobenchmark or measured jank result exists.
- The Nix Android build path still requires `--option sandbox false`.
