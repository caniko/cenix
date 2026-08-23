# ADR 0001: Kotlin Android host

## Status

Accepted.

## Context

Cenix is an installable HOME. Android HOME, `LauncherApps`, `RoleManager`, and Room are Java/Kotlin APIs.

## Decision

UI, catalog, Room, and emergency chrome live in Kotlin (`android/app`). Rust does not own Android types.

## Consequences

Host tests use Robolectric/JUnit. Native ranking is called through UniFFI only after the host has a catalog.
