# ADR 0013: Typed workspace reducer

## Status

Accepted.

## Context

Workspace behavior must be deterministic without making Rust a durable state owner.

## Decision

Room supplies a typed `WorkspaceSnapshot`. Kotlin sends one typed `WorkspaceCommand` through UniFFI. Safe Rust validates the generation and invariants, then returns a typed `WorkspaceTransition` or `WorkspaceError`. Rust retains no canonical workspace state.

## Consequences

The bridge contains records and enums, not JSON, protobuf, byte arrays, or handwritten JNI DTOs. Host tests can replay commands deterministically.
