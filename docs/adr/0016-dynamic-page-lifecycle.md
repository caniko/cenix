# ADR 0016: Dynamic page lifecycle

## Status

Accepted.

## Context

Fixed empty pages persist UI artifacts and make page identity depend on position.

## Decision

Pages have stable IDs and contiguous ranks. Edge drag may append one page. Commands other than explicit page/grid/cancel operations trim every empty trailing page while retaining at least one page. Room's monotonic allocator prevents removed page IDs from being reused.

## Consequences

Page creation and removal are transition data. Migration creates page two only when legacy screen one contains an item.
