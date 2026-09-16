# Customization validation checkpoint

Local verification on atlas, 2026-09-16. These results describe the uncommitted
working tree, not a tagged or published APK.

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` (real UniFFI boundary) | 97 tests, zero failures/errors/skips |
| `:app:lintDebug` | Passed |
| `cargo test --workspace --locked --offline` | 68 Rust tests passed |
| `cargo fmt --check` | Passed |
| `cargo clippy --workspace --all-targets --locked --offline -- -D warnings` | Passed |
| `scripts/check-bindings.sh` | Working-tree Kotlin matches generated UniFFI output |
| `scripts/check-foss.sh` | Passed |
| `scripts/check-performance-static.sh` | Passed; not a runtime benchmark |
| actionlint 1.7.7 on `.github/workflows/host.yml` | Passed |
| `git diff --check` | Passed |
| `scripts/check-licenses.sh` | Blocked locally: `cargo-deny` unavailable |

Android checks used the checked-in Gradle 8.12.1 wrapper, JDK 21 and the installed
SDK. A temporary init script selected the project's declared repositories for the
offline cache; dependency checksum verification remained enabled and the global
Gradle configuration was left unchanged. Rust checks used the installed
nightly-2025-11-11 toolchain with the available GCC/BFD linker.

## Rust-first architecture (2026-09-16)

Portable policy lives in `cenix-core` with Rust unit tests as the primary
coverage; Kotlin delegates over UniFFI with a pure-Kotlin fallback that exists
solely for omitNative builds and emergency mode (ADR 0009):

- Profile discovery quarantine and classification (`profiles.rs`; 7 tests).
  Kotlin `ProfileDiscovery`/`ProfileClassifier` delegate with fallback.
- Icon-pack XML indexing (`iconpack.rs`; 5 tests). Kotlin `IconPackXml.parseBytes`
  delegates with fallback; the asset path uses it, compiled-XML path keeps the
  platform parser. Parity vectors assert both entries agree.
- Drawer scoping for restore targets (`scope_drawer_for_targets`; tested in
  `backup.rs` plus a Kotlin parity test).
- Full backup artifact codec (`codec.rs`; 17 tests): strict parsing,
  byte-exact canonical encoding pinned to the `valid-v2.json` golden fixture,
  v1 import, envelope checksum, restore journals, shape validation. The 1278-line
  Kotlin parser is replaced by a thin delegate; its 28 vector tests moved to Rust.
- Unit tests load the host cdylib over JNA (`build.gradle.kts` sets
  `jna.library.path`; `cargo build -p cenix-ffi` runs before them in CI via
  `host.yml` and in `scripts/run-host-tests.sh`). FFI paths were additionally
  proven by temporarily sabotaging each Kotlin fallback and observing green runs.
- Kotlin `EmergencyFilter` intentionally stays duplicated: emergency HOME must
  filter with no native library loaded.
- No new dependencies were added: strict JSON and XML are hand-rolled bounded
  parsers (serde/serde_json remain transitive-only), and SHA-256 is
  self-contained in `codec.rs`.

## Coverage added

- v2 golden wire fixture with per-profile taxonomy; unchanged v1 import;
  future-version rejection; required v2 drawer section; absent-pack references
  and work-profile remapping; personal-only export drops the work taxonomy.
- Room 9→10 restore-journal migration; failed second preference write followed by
  closing/reopening the database, replaying the scoped snapshot and completing once.
- Restore barrier: normal workspace commits back off while reconciliation is pending.
- Reload generation gate; profile-kind preservation across user-type lookup failures.
- Private override rejection and legacy cleanup, independently of UI visibility.
- Visible-result DPAD focus, focus-preserving drawer rebuilds, section
  grouping/private exclusion, full icon catalog search beyond the old display
  limit, malformed/deep/DOCTYPE XML rejection, missing-pack closed fallback.
- Stable collision-resistant category IDs, per-profile taxonomy migration,
  cold-start launcher-intent routing with deferred work section.
- Search results carry the reload lifecycle generation: profile lock/removal and
  reloads invalidate pending searches so stale results cannot republish private
  content (SearchControllerTest).
- Restore journals carry an explicit version. Versionless dev journals skip drawer
  replay with a warning; malformed versioned journals fail loudly.
- Restore completion runs inline in the reload flow before reconciliation; mutations
  deferred by the pending-restore barrier are retried once, and one-shot install
  placements are preserved across the retry.
- Legacy reset additionally clears assignments orphaned by removed definitions.

## Follow-up pass (2026-09-15)

109 Android unit tests and lint pass with no failures. Changes since the checkpoint
above: pending-restore journals no longer block ordinary workspace commits as fatal
errors (deferred with a log, retried after widget-host recovery completes staged
restores even when staged after first recovery); profile broadcasts invalidate private
lists, menus and folders on the main thread; first-discovery profiles of unknown type
are quarantined (hidden, rows preserved) instead of purged; obsolete global taxonomy
keys reset once behind a marker rather than migrating ownership; drawer mode restores
with the taxonomy; invalid drawer modes are rejected at parse.

## Follow-up pass (2026-09-16, maintained-crate migration)

71 Rust tests and 97 Android tests pass with no failures, plus lint, clippy
`-D warnings`, fmt, bindings, FOSS/perf static gates, and `git diff --check`.
Changes since the checkpoint above:

- `serde_json` now tokenizes backup JSON (strict duplicate-key/order-preserving
  model, exact error reasons); `sha2` replaces hand-rolled SHA-256; `quick-xml`
  replaces the hand XML tokenizer with end-name checking (mismatched tags now
  rejected). Pinned in `Cargo.lock`, resolved fully offline.
- Payload escaping matches AOSP `JsonWriter` exactly (verified against source:
  short forms, U+2028/U+2029, no HTML escaping); the golden fixture still
  round-trips byte-identically.
- v2 envelopes without the required drawer section are corrupt (was: silently
  defaulted); the encoder refuses output over the reader limit.
- Local verification note: the environment's rustup nightly linker wrapper
  referenced a nonexistent nix-store path, so linking used a direct `ld.lld`
  invocation (original preserved at `/tmp/ld.lld.bak`); the pinned-toolchain
  nix CI path is unaffected.

## Follow-up pass (2026-09-16, Rust-first migrations)

81 Rust tests and 102 Android tests pass with no failures, plus lint, clippy
`-D warnings`, fmt, bindings, FOSS/perf/license/REUSE gates, and
`git diff --check`. Changes since the checkpoint above:

- `reconcile_profiles` folds a full discovery refresh into one Rust call:
  failed serial lookups hide and preserve (never remove or reclassify);
  only serials absent from every observation are listed for cleanup.
  Kotlin collects Android observations and correlates handles.
- New `drawer.rs` module owns category rules (identifier syntax, title
  hygiene, ordering, assignment, section keys); live editing, projection,
  export, and restore share them. `backup.rs`/`codec.rs` duplicates removed.
- Icon XML enforces the single-root document contract and attribute bounds on
  every element in both Rust and Kotlin; native input rejection no longer
  falls through to the Kotlin parser (unavailable-native still does).
- `quick-xml` upgraded 0.38.4 to 0.41.0 for two RUSTSEC advisories; license
  gate (`cargo-deny` advisories/bans/licenses/sources) and `reuse lint` pass.
- Search results carry the request sequence as well as the lifecycle
  generation; superseded queries and close() can no longer publish stale
  content (new deterministic test).
- Icon rendering leaves the manager monitor, runs on a dedicated bounded pool
  with an 8s deadline, and never rasterizes on the UI thread; hangs degrade
  to ordinary-icon fallback. Full process isolation remains follow-up
  (isolated processes cannot reach pack Resources; needs a secondary process
  plus an Application-start guard).
- Provenance: `compare-release-apks.sh` copies a compared build as the upload
  artifact and records its digest; the workflow uploads that copy plus the
  structured manifest. `sign-release.sh` can bind provenance to the manifest
  (`CENIX_SOURCE_MANIFEST`) and enforce the expected certificate
  (`CENIX_EXPECTED_CERT`) instead of trusting the signing checkout's HEAD.
- Native-path proof: profile-reconcile and drawer-ordering tests pass with
  their Kotlin fallbacks sabotaged to throw; only the direct fallback-mirror
  test fails under sabotage, as designed. Sabotage lines fully reverted.
- `run-host-tests.sh` no longer rewrites Gradle locks during verification;
  unit-test native library path is overridable via `CENIX_JNA_PATH`.
- Experimental note: Cranelift and cargo-fuzz could not be evaluated here
  (pinned-toolchain component and fuzzer binaries absent offline); coverage
  uses dependency-free deterministic sequence tests in-tree instead.
- Local toolchain note: ambient `cargo` is stable 1.97 without a linker, so
  Rust gates ran on the installed nightly-2025-11-11 (not the pinned
  nightly-2026-02-28) with a nix-store GCC wrapper; the pinned nix CI path is
  unaffected. The Gradle suite was force-rerun after the native rebuild
  (plain `--offline` had short-circuited as up-to-date).

## Device-smoke readiness pass (2026-09-16)

82 Rust tests and 103 Android tests pass with no failures (force-rerun,
timestamp-verified), plus lint, clippy `-D warnings`, fmt, bindings, and
`git diff --check`. Safety fixes for separate-profile testing:

- Unresolved profiles can no longer publish `AVAILABLE`: flags observed
  alongside a failed serial lookup are distrusted in Rust
  (`reconcile_profiles`) and mirrored in the Kotlin fallback (new test).
- Cold-start `retainProfiles` additionally preserves every locally known
  serial (`knownSerials()` on both stores, new test), so a transient lookup
  failure before first in-memory discovery cannot purge rows for a profile
  simply unseen this boot. Workspace `DropMissing` was already fail-safe
  (only authoritative profiles drop).
- New `scripts/device-smoke.sh` (syntax-checked, refusal paths exercised):
  requires `CENIX_DEVICE_SERIAL` + numeric `CENIX_TEST_USER`, aborts unless
  the test profile is foreground and mustang/GrapheneOS match, refuses when
  the package exists for other users (shared-code risk), audits native
  symbols, installs `--user`-scoped only, records SHA + tree identity. No
  reboot, user, settings, fixture, HOME-role, or data operations.
- NOT done here: fresh ARM64 rebuild (`cargo-ndk` unavailable and `nix
  build` denied in this sandbox; run `assemble-debug.sh` where builds are
  allowed, then the smoke script). The packaged `jniLibs` ARM64 `.so`
  predates the current FFI surface — do not device-test any existing APK.

## Installer hardening pass (2026-09-16)

`scripts/device-smoke.sh` rewritten to close the fail-open gaps; all 9 cases
of the new `scripts/test-device-smoke.sh` fake-adb harness pass (8 refusals
issue zero mutating device commands; success issues exactly one
`install -r -t --user <test-profile>`):

- Per-user package enumeration with fail-closed queries; no shared-package
  bypass remains — any other holder is a hard refusal.
- Owner user 0 rejected; GrapheneOS version required; APK package identity
  checked via aapt; native freshness enforced (APK's arm64 `.so` must equal
  the tree `.so`, which must postdate all Rust sources and bindings).
- Foreground user checked before and rechecked immediately before install.
- The harness caught two real issues during development (fake-adb exit codes
  and grep dialect), both fixed; the smoke script itself needed no changes
  after the rewrite.
- NOT done here: fresh ARM64 build (no Android Rust targets, no cargo-ndk,
  and store realisation denied in this sandbox). Build on a capable machine
  with `scripts/assemble-debug.sh`, then run the smoke script — it refuses
  any stale tree automatically.
- Follow-up fixes: harness cases now assert the exact refusal message
  (previously exit-code only, which masked vacuous passes); new
  foreground-switch case covers a mid-run profile change; `aapt | head`
  SIGPIPE hazard removed; the APK itself must postdate all app sources
  (not just the native lib); `apksigner verify` integrity plus signer
  certificate are recorded as evidence. All 10 harness cases pass.

## Device definition pass (2026-09-16)

- Pixel 10 Pro XL (`mustang`, `arm64-v8a`, foreground user 10, build
  `2026091001`) detected read-only over USB; `ro.grapheneos.version` is
  empty on this device, so OS identity now comes from the explicit
  `CENIX_GOS_VERSION` expectation plus build reporting, not that property.
- `harbor-android.lib.mkAndroidDeviceTools` added (enroll/verify, definitions
  are runtime JSON, no serials in the store; USB transport required;
  overwrite refused). Harbor eval-level and runtime checks added; the runtime
  check logic additionally passes as a local fake-adb functional test.
  Pre-existing `nix flake check` treefmt breakage unrelated to this change.
- Cenix stores identity only (serial/product/model) in untracked
  `.android-device.local.json` (0600, gitignored, live-verified);
  `device-smoke.sh` accepts `CENIX_DEVICE_DEF` with mismatch refusal and
  inline fallback. Harness extended to 13 passing cases.
- No install performed; fresh-APK requirement unchanged.

## Not release evidence

No signed ARM64 device qualification, real TalkBack session, stock runtime baseline,
power-loss test, F-Droid lint/build, independent F-Droid rebuild, or GitHub artifact
for this working tree has been produced. `v0.2.0` was not a local tag when checked.
No commit, tag, push, signing or publication was performed in this pass.

The F-Droid metadata is still a draft. A release needs reviewed/committed source,
the intended signing identity and protected CI release setup, a qualified GrapheneOS
device, actual F-Droid build results and reproducibility artifacts. Category-management
UI completeness, cold-start launcher-intent routing and remaining visual parity are
tracked honestly as partial in `parity-capabilities.md`.
