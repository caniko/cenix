# ADR 0006: Classify before implementing

## Status

Accepted.

## Context

Launcher3 source contains privileged Recents, `QUERY_ALL_PACKAGES`, widget restore, and Private Space UI. Copying features from source would claim parity we cannot ship as a 3p HOME.

## Decision

Every feature is one of: `INSTALLABLE_PUBLIC`, `ROLE_HOME_GATED`, `USER_AUTHORIZED`, `SIGNATURE_OR_SYSTEM`, `INTENTIONALLY_OMITTED`, `UNKNOWN_REQUIRES_SPIKE`. Ledger: `docs/parity-capabilities.md` and `spec/parity/ledger.md`.

Do not implement `SIGNATURE_OR_SYSTEM`. Do not claim Recents/Quickstep.

## Consequences

v1 is phone HOME + two-page workspace + All Apps + search + launch + emergency. Widgets and Recents stay out.
