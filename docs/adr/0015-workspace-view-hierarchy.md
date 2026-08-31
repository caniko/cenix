# ADR 0015: Custom workspace View hierarchy

## Status

Accepted.

## Context

`GridView` cannot represent paged logical cells, spans, edge paging, and one drag surface without conflating persistence and pixels.

## Decision

Classic Android Views implement `WorkspacePager`, `CellLayout`, `HotseatView`, `PageIndicator`, and `DragLayer`. Kotlin maps persisted logical cells to current pixel geometry. Android framework objects never cross into Rust.

## Consequences

Pointer input, layout, accessibility, and rendering stay Kotlin-only. Workspace and hotseat use no `GridView`.
