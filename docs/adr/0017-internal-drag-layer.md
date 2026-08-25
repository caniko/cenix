# ADR 0017: Internal drag layer

## Status

Accepted.

## Context

Android system drag exports interaction state outside the launcher hierarchy and does not provide the required internal cancellation and edge-page control.

## Decision

Long press creates a Kotlin `LauncherDrag` payload in `DragLayer`. `LauncherRoot` routes subsequent pointer events internally. Motion updates only local hover/page state. One drop creates one typed workspace command and one Room transaction. Package changes, invalid targets, cancellation, and activity destruction clear the local payload.

## Consequences

No high-frequency pointer event crosses UniFFI. No `startDragAndDrop` or `DragEvent` remains.
