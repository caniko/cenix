# ADR 0007: Clean-room behavior copy

## Status

Accepted.

## Context

GrapheneOS Launcher3 is AOSP-derived Java. Cenix must not become a fork of that tree.

## Decision

Inspect public behavior and public APIs. Do not copy AOSP Java into this repo. Pin the inspected commit in `docs/reference-baseline.md`. User-visible HOME behavior may match GrapheneOS where public APIs allow it.

## Consequences

Grid numbers, Recents, and Private Space chrome from source are inventory, not a license to paste code. Pixel 10 Pro XL live fingerprint is unobserved until a `mustang` is attached.
