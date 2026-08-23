# ADR 0008: No Cuscon / vendor icon packs

## Status

Accepted.

## Context

Some launchers ship vendor icon packs or Cuscon assets. Those are not public GrapheneOS HOME requirements and would add assets we do not own.

## Decision

No Cuscon. No bundled third-party icon pack. Default icons come from `LauncherApps`. A later icon-pack reader may use public resources only.

## Consequences

No icon-pack fixtures in this slice. Icon-loader bounds stay a later host-side guard, not a native ABI.
