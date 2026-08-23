# ADR 0002: Safe Rust ranks apps

## Status

Accepted.

## Context

Filter/rank must be testable without an emulator and must stay bounded.

## Decision

`cenix-core` is `#![forbid(unsafe_code)]` and owns `filter_and_order_apps`. `cenix-ffi` is a typed UniFFI wrapper. Emergency Kotlin (`EmergencyFilter`) is a fallback copy of the same rank rules, used only when native is unavailable.

## Consequences

Host `cargo test` covers ranking. Emergency and native can drift; keep both small and compared by tests when they change.
