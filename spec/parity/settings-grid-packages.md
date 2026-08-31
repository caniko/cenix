# Settings, grid, and package parity invariants

## Durable authority

1. Room schema v8 is the sole durable authority for launcher preferences, the selected grid, and workspace.
2. `launcher_settings` contains one normalized singleton row keyed by `singletonId = 1`.
3. A grid selection and its workspace transition commit in one Room transaction.
4. Failed, stale, impossible, repeated, or cancelled migration leaves both settings and workspace unchanged.
5. Package lifecycle and installer progress never enter Room.

## Grid policy

1. Only the five reference-derived compatible phone grids are selectable.
2. Rust validates the source snapshot and plans all movement before Room mutation.
3. Existing valid cells, spans, page IDs, item IDs, folder IDs, folder-member IDs/order, profile IDs, and app-widget IDs remain stable.
4. Hotseat entries that no longer fit move to workspace in stable rank and item-ID order.
5. Workspace overflow scans from the source page in visual row/column order and allocates monotonic pages only when required.
6. Empty trailing pages are removed deterministically; the final page is never removed.
7. Widgets may shrink only to an Android-supplied public minimum span. An impossible minimum returns `WidgetTooLarge`; no widget is deleted or undersized.

## Package policy

1. Package identity is package name plus stable profile serial; component identity additionally includes class name.
2. `READY`, `INSTALLING`, `UPDATING`, `SUSPENDED`, `DISABLED`, `ARCHIVED`, and `TEMPORARILY_UNAVAILABLE` are transient presentation states.
3. Active installer sessions are callback/enumeration driven; there is no polling loop.
4. Temporary states retain durable workspace, hotseat, folder, shortcut, and widget rows.
5. `onPackageRemoved` is the permanent-removal event and reconciles only the affected profile.
6. Locked private-profile identities remain absent from UI, logs, diagnostics, and package-session presentation.
7. Only `READY` applications may be placed. Archived applications may request platform restore; other non-ready states do not launch.

## Settings surface

1. The Activity is non-exported and entered through an explicit Cenix intent.
2. Controls use classic Android Views, stable IDs, localized labels, at least 48 dp targets, locale RTL, keyboard/D-pad focus, and recreation-safe Room state.
3. Reset requires confirmation and clears the widget host before local Room state.
4. HOME selection uses the public user-authorized role request.
5. Build identity reports version, build type, and embedded source commit without installed-application identities.

## Out of scope

Icon packs, backup/import/export, Quickstep/Recents, feeds, weather, search services, arbitrary grids, hidden APIs, reflection, shell-based production behavior, and network services remain intentionally omitted.
