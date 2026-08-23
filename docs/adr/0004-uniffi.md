# ADR 0004: UniFFI, not JNI/JSON

## Status

Accepted.

## Context

An earlier JNI + JSON filter protocol (`FilterProtocol`, `NativeBridge`, `cenix-jni`) existed. It was stringly typed and easy to desync.

Compared on this repo:

| Path | What we kept / dropped |
| --- | --- |
| JNI + JSON | Dropped. Manual JNI, JSON schema, no generated Kotlin types. |
| UniFFI records | Kept. `App` / `AppId` / `EngineError` generate Kotlin. |

Harbor-JS / Bun / a JS runtime were never a HOME-safe option (no WebView, no INTERNET).

## Decision

`uniffi::export` `filter_and_order_apps`. Generated Kotlin is committed and checked (`scripts/check-bindings.sh`). Emergency mode must not construct those types.

## Consequences

Binding drift is a host-gate failure. `-PomitNative` APKs must still start.
