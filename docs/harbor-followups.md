# Harbor follow-ups

Cenix consumes published remotes only. Do not edit Harbor, Canix, or Fleetix from this work.

## Pins

| Input | Flake rev |
| --- | --- |
| harbor-rs | `35ebc37423ff391e117cf4417390e4b862e48cdc` |
| harbor-android | `751a9fcc896afa764b690cd0711c80decbdb7173` |

Local harbor-rs checkout may be ahead (`a49c2554…`). Cenix stays on the flake pin.

## Used as-is

From harbor-android `lib/default.nix`: `mkAndroidSdk`, `mkAndroidApk`, `mkAndroidDevShell`.
From harbor-rs: `mkToolchain`, `mkCross`, `mkDevShells`.

Cenix adds a second `mkAndroidSdk` composition (`includeEmulator` / `includeSystemImages`) for `devShells.emulator` only. That is flake wiring, not a new Harbor API.

## Not invented

No Cenix-specific Harbor helpers. UniFFI generate/check, APK audit, 16K page check, and emulator conformance stay as repo scripts.

## Later (Harbor, not this repo)

If more projects want an emulator SDK flavor, that belongs in harbor-android. Not needed for this slice.
