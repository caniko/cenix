# ADR 0009: Emergency HOME without native

## Status

Accepted.

## Context

A missing `.so`, a UniFFI load failure, or a crash loop must not brick HOME.

## Decision

User-forced and native-load failures persist `emergency` in Room until retry-native succeeds or the user resets. Crash-loop (3 incomplete startups in 60s) uses emergency for the current process only; expired failures do not persist. `activeFilter()` returns `EmergencyAppFilter` and never constructs `NativeAppFilter` / UniFFI types while emergency. `loadNative()` constructs then probes `filter(emptyList(), "", emptySet())`. Factory or probe throw → persist emergency. Database-open failure uses a memory store (nonpersistent emergency) and never deletes the DB.

Debug: `FORCE_NATIVE_FAILURE`. `-PomitNative` omits `libcenix_ffi.so`. `reload()` marks healthy only after a list is rendered.

## Consequences

Omit-native APKs are valid HOME. Retry/reset are explicit user actions. Process death keeps the banner.
