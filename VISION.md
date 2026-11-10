# Cenix vision

An installable HOME launcher for GrapheneOS, built clean-room against public
Android APIs. The first slice lists apps, filters and launches them, survives
process death, and keeps working when native code fails; the wider goal is
launcher parity with GrapheneOS Launcher3 on a Pixel phone without forking or
copying that tree. Device target is Pixel 10 Pro XL (`mustang`); the build is an
API 35 debug APK and the AOSP `x86_64` emulator is explicitly not GrapheneOS.

## Problem

GrapheneOS ships Launcher3 as a system component. Anyone who wants different
launcher behaviour must either accept it or build a launcher from scratch - and
a launcher is a hard, system-integrated surface: HOME role, package callbacks,
widgets, shortcuts, work profiles, Private Space, backup and restore. Guessing
at behaviour fails silently, and copy-pasting AOSP Java would make the project a
fork with a licensing and maintenance debt. A clean-room implementation with
typed boundaries is the way to get parity without inheriting the tree.

## Principles

- Clean-room. Inspect public behaviour and public APIs only; no AOSP Java is
  copied, and the inspected commit is pinned in `docs/reference-baseline.md`.
- Evidence classes, not claims. Every capability carries a class
  (`INSTALLABLE_PUBLIC`, `ROLE_HOME_GATED`, `USER_AUTHORIZED`,
  `SIGNATURE_OR_SYSTEM`, `TRANSPORT_CONTROLLED`, `INTENTIONALLY_OMITTED`, ...) and
  evidence keys (`SRC`, `API`, `AOSP_EMU`, `GOS_DEV`, `SYSTEM`, `UNKNOWN`).
  Source presence is not user-visible proof, and an emulator run is never device
  proof.
- Typed boundaries between layers. `cenix-core` is safe filter/rank with
  `#![forbid(unsafe_code)]`; `cenix-ffi` exposes typed UniFFI operations; the
  Android app owns Room for workspace and settings state, and drawer-category
  and icon-pack preferences live in separate SharedPreferences stores.
- Degrade, do not crash. The launcher stays usable if native code fails;
  emergency mode never imports generated UniFFI types; an unwritable diagnostics
  directory cannot take the app down.
- Room is the durable store for workspace and settings state, with tested
  migrations through the current schema version; drawer-category and icon-pack
  preferences are separate stores, journaled with the workspace snapshot so a
  restore keeps both.
- Privacy by omission. No internet, WebView or JS runtime; no
  `QUERY_ALL_PACKAGES` (the app uses `<queries>` MAIN+LAUNCHER); private and
  unknown per-app customizations are rejected rather than leaked.
- Reproducible release artifacts: byte-identical unsigned release rebuilds with
  pinned SHA-256 and a validated CycloneDX SBOM.

## Scope

- HOME shell: workspace pager, hotseat, folders, drag and drop, all-apps drawer
  with search, categories and icon packs (a Cenix extension).
- Package lifecycle: install/update/remove/suspend/disable callbacks, automatic
  new-app placement, transient context actions.
- Widgets and shortcuts: real `AppWidgetHostView`, restore/ID remap, pinned
  shortcuts and pinned-widget confirmation.
- Profiles and Private Space: work tabs, quiet mode, DPC-allowed widgets,
  lock-time private identity suppression.
- Settings: finite reference-derived phone grids, atomic Rust/Room migration,
  HOME role request, diagnostics export, reset.
- Backup: bounded personal-only `BackupAgent` artifact and SAF JSON
  layout export/import.

## Non-goals

- Tablets, desktop grids, Recents/Quickstep, launcher data provider, the
  organizer, app locking and Cuscon-style runtime: omitted, with the class
  recorded in the parity table.
- Forking or vendoring Launcher3. The inspected source is inventory, not a
  license to paste.
- Any network surface in the launcher.
- Claiming stable support from emulator evidence alone; `GOS_DEV` mustang proof
  is still outstanding.

## Direction

The roadmap is P1 through P5: typed launcher and shell, folders, shortcuts,
widgets, profile boundary, grid and package-lifecycle settings, then
user-authorized notification dots, automatic placement, wallpaper appearance,
dynamic colours and public adaptive themed icons. Remaining items are tracked
with the evidence they still need - disabled/suspended chrome, a TalkBack pass,
and external backup-transport proof - while signature-level features stay
omitted.

## Place in the fleet

Built with the fleet's Android toolchain: `harbor-rs` and `harbor-android` pins,
a Nix dev shell, a host CI workflow covering Rust and Gradle gates, and an
isolated emulator conformance runner. Device work targets the operator's own
GrapheneOS phone; nothing in Canix consumes the APK at runtime.
