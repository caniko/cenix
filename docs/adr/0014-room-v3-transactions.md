# ADR 0014: Room v3 generation transactions

## Status

Accepted.

## Context

Pages, containers, spans, and stable identifiers require more structure than Room v2's screen integer.

## Decision

Room v3 is the sole durable authority. Metadata stores the generation, grid, and monotonic item/page allocators. Pages and items have stable primary keys. A transaction accepts only the expected generation, advances by exactly one for a changed transition, verifies exact equality for a no-op, and rolls back every row on failure. Migration 2 to 3 preserves both workspace screens, hotseat entries, components, profiles, and cells without destructive fallback.

## Consequences

Process death after commit is recovered by reading Room. Stale or malformed transitions cannot partially replace rows or silently pass as no-ops.
