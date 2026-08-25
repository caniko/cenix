# ADR 0018: Deterministic reorder

## Status

Accepted.

## Context

Folders are out of scope, but dropping an application on an occupied cell needs stable behavior.

## Decision

`Reorder` moves the dragged item to the requested cell and relocates the single-cell occupant to the nearest vacancy. Ties use Manhattan distance, then row, then column. Direct `Move` rejects occupied cells.

## Consequences

Equivalent snapshots and commands produce byte-equivalent ordered transitions. Multi-span occupant reorder remains rejected until widgets exist.
