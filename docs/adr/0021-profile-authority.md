# ADR 0021: Android-owned profile state

## Status

Accepted.

## Decision

Android owns live profile discovery, type, access, authentication, applications, shortcuts, widgets, and resources. Kotlin maps public API 35 state to typed descriptors containing only stable profile serial, kind, and access. Rust deterministically projects those descriptors per launcher surface and applies explicit authoritative-profile reconciliation or permanent-profile removal. Room remains the sole durable workspace authority.

Temporary quiet, locked, or inaccessible state never deletes durable items. Only a profile-removal event sends the typed removal command. Private identities are cleared before lock requests, private workspace/pin admission is rejected, and Cenix never requests `ACCESS_HIDDEN_PROFILES_FULL`.

## Consequences

Work resources return after unquiet without rebuilding workspace state. Private Space is limited to public third-party HOME behavior. Platform authentication, DPC policy, and unsupported privileged Launcher3 behavior remain outside Cenix.
