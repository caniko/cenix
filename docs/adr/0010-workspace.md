# ADR 0010: Two-page phone workspace

## Status

Superseded by ADRs 0013, 0014, 0015, and 0016.

## Context

HOME needs a grid, not only All Apps. Drag, widgets, and folders stay out.

## Decision

Room v2 adds `workspace_items`. Phone grid is picked at runtime from GrapheneOS Launcher3 `e5fde8f4` phone thresholds (smallest non-Stubby display-option). Two screens; swipe to change page. Pin fills the current screen, then the other. Long-press All Apps pins; drag a pin to move or drop off-grid to unpin. Items outside the current grid stay stored and stay hidden.

## Consequences

No invented destructive migration. Grid choice is not a user setting. AOSP closest-distance picking waits until users can choose grids.
