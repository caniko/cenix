# ADR 0020: Typed shortcuts and platform-owned actions

## Status

Accepted.

## Decision

Represent durable shortcuts as typed `(package, shortcut ID, profile)` identities in the Rust reducer, UniFFI, and Room v5. Resolve labels, icons, launchability, and publication state from `LauncherApps`; keep context popups and pointer state transient in Kotlin.

Incoming pin requests use Android's validated `CONFIRM_PIN_SHORTCUT` contract and require explicit Add/Cancel. Platform acceptance precedes Room placement so rejected requests cannot leave durable items; a failed placement restores the prior platform pin set. App info and uninstall always hand off to Android-owned surfaces.

## Consequences

Room remains the only durable workspace authority. Cenix stores no platform labels or icons, performs no package-specific protocol, and adds no second snapshot or mutable Rust engine. Widgets and pinned-widget confirmation remain separate future work.
