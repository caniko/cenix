# 0019: Separate production HOME and All Apps surfaces

## Context

The P1 conformance layout stacked status, recovery, workspace, search, and app-list controls in one screen. A production HOME must keep the workspace primary and expose All Apps and recovery only in their relevant states.

## Decision

Use one layered classic-View hierarchy with explicit `HOME`, `ALL_APPS`, and `EMERGENCY` states. Kotlin owns swipe, Back, HOME-intent, IME, accessibility, and internal-drag transitions. Durable workspace state remains in Room and deterministic workspace commands remain in Rust.

## Consequences

Normal HOME contains only the wallpaper-backed workspace, page indicator, and hotseat. All Apps is a separate full-screen surface. Emergency mode exposes Kotlin search and recovery without allowing navigation back into native-backed HOME.
