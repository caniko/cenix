# ADR 0009: Emergency HOME without native

## Status

Accepted.

## Context

A missing `.so`, a UniFFI load failure, or a crash loop must not brick HOME.

## Decision

`CrashLoopGuard` persists `emergency` in Room. Threshold: 3 incomplete startups in 60s. `activeFilter()` returns `EmergencyAppFilter` and never constructs `NativeAppFilter` / UniFFI types while emergency. `loadNative()` constructs then probes `filter(emptyList(), "", emptySet())`. Factory or probe throw → persist emergency.

Debug: `FORCE_NATIVE_FAILURE`. Release/debug packaging: `-PomitNative` excludes `*.so`. `reload()` applies filter then chrome so a failed probe still paints the banner.

## Consequences

Omit-native APKs are valid HOME. Retry/reset are explicit user actions. Process death keeps the banner.
