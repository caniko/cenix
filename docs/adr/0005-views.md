# ADR 0005: Kotlin Views

## Status

Accepted.

## Context

The slice is a list, a search field, and a banner. Compose would add a runtime and a design-system choice for no product gain.

## Decision

`AppCompatActivity` + XML layouts + `ListView`. No Compose, no custom launcher framework.

## Consequences

Workspace/hotseat later can stay Views or revisit then. Do not add Compose “for later.”
