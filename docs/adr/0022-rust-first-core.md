# ADR 0022: Rust-first core, thin Kotlin adapters

## Status

Accepted.

## Context

Portable launcher policy was implemented twice: once in safe Rust
(`cenix-core`) and again in Kotlin for Android integration. The duplication
covered profile quarantine, icon-pack indexing, drawer scoping, and the full
backup artifact codec. A Rust UI was also proposed for evaluation.

## Decision

Rust is the default for portable behavior, validation, state transitions,
bounded file parsing, and tests:

- Profile discovery quarantine, classification, and full-refresh
  reconciliation (`profiles.rs`, `reconcile_profiles`): failed observations
  hide and preserve, only genuinely absent serials are removed.
- Icon-pack index parsing (`iconpack.rs`, maintained XML tokenizer with
  end-name checking plus Cenix item policy).
- Drawer restore scoping (`scope_drawer_for_targets`).
- Drawer category rules (`drawer.rs`): identifier syntax, title hygiene,
  ordering, assignment, and section keys shared by live editing, projection,
  export, and restore. Android keeps preferences IO, localized strings,
  locale collation, and the platform category mapping.
- Backup envelope and restore-journal codec (`codec.rs`, hand-rolled strict
  JSON with byte-exact canonical encoding pinned to the golden fixtures).
- Small maintained dependencies where they remove custom infrastructure:
  `serde_json` (JSON grammar), `quick-xml` (well-formed XML), `sha2`
  (checksums), all pinned in `Cargo.lock` with offline-verified resolution.
  Cenix-specific policy (bounds, duplicate-key rejection, canonical escaping
  and ordering, typed errors) stays hand-written and tested.

Kotlin delegates over UniFFI with a pure-Kotlin fallback that exists solely
for omitNative builds and emergency mode (ADR 0009). Unit tests load the host
cdylib over JNA, so CI exercises the real FFI path; each delegation was
additionally proven by temporarily sabotaging its fallback. The approved
exception stands: bounded artifact bytes may cross UniFFI into parsers that
return typed records and errors; launcher commands stay typed.

Explicitly out of scope:

- Restore phase transitions stay in Room SQL, which enforces them atomically.
  Extracting them would fork, not consolidate, the state machine.
- `EmergencyFilter` stays duplicated in Kotlin: emergency HOME must filter
  with no native library loaded.
- Views, drag/drop, widgets, and persistence stay Kotlin/Views. A Rust UI
  (Dioxus Native/Blitz) cannot host `AppWidgetHostView`, needs its own
  TalkBack/widget bridges, and its dependencies are unreachable from the
  offline build. Revisit only with a bounded prototype that passes the widget,
  accessibility, and packaging gates.

## Consequences

`cenix-core` unit tests are the primary policy coverage; Kotlin keeps parity
vectors and mapping tests. `cargo build -p cenix-ffi` must run before Gradle
unit tests (CI `host.yml` and `scripts/run-host-tests.sh` do this).
