# Implementation status

First recoverable vertical slice.

- Native filter lives in `cenix-core` and is reached only through `cenix-jni`.
- Kotlin owns discovery, launch, Room, crash-loop detection, and emergency filtering.
- JNI libraries are copied into `android/app/src/main/jniLibs/` by `scripts/build-jni.sh`.
- Host tests: `cargo test --workspace`, `bun test` in `tools/`, `./gradlew :app:testDebugUnitTest`.
- Device/emulator smoke is optional (`scripts/install-debug.sh`).
- compileSdk/targetSdk: 35 (GrapheneOS 17 platform not in the current androidenv pin).
- `bun.lock` is omitted: Bun 1.3.14 deletes an empty lockfile for a zero-dependency `tools/` package. `packageManager` is pinned in `tools/package.json`.
