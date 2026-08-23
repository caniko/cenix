# ADR 0010: One-page phone workspace

## Status

Accepted.

## Context

HOME needs a grid, not only All Apps. Drag, hotseat, widgets, and folders stay out.

## Decision

Room v2 adds `workspace_items`. Phone grid is picked at runtime from GrapheneOS Launcher3 `e5fde8f4` phone thresholds (smallest non-Stubby display-option). One screen. Long-press All Apps pins; long-press a cell unpins. Items outside the current grid stay stored and stay hidden.

## Consequences

No invented destructive migration. Grid choice is not a user setting. AOSP closest-distance picking waits until users can choose grids.
