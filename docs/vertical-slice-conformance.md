# Vertical slice conformance

Cenix is an installable HOME app: list apps, filter them, launch them, survive process death, and keep working if native code is missing.

Device target remains Pixel 10 Pro XL (`mustang`) / GrapheneOS `2026081300`. The AOSP API 35 `google_apis` x86_64 emulator is not that device.

## Proven on host

- Rust `cenix-core` ranking/bounds tests
- UniFFI Kotlin bindings match generated output and have no trailing whitespace
- Room persists crash-loop / emergency across a new `CrashLoopGuard`
- Emergency path never constructs `NativeAppFilter` or generated UniFFI types
- Native probe failure (`filterFactory` throw or probe throw) enters persisted emergency
- Debug APK audit: HOME exported, no `INTERNET`, no `QUERY_ALL_PACKAGES`, no WebView, `libcenix_ffi.so` present

## Proven on emulator

Run only from `nix develop .#emulator`. `nix develop` (default) does not fetch the ~1.6 GiB system image.

```bash
nix develop .#emulator --command scripts/emulator-conformance.sh
```

That script:

1. Creates AVD `cenix-api35` if needed
2. Installs the debug APK and takes the HOME role
3. Checks the search field is present
4. Installs `com.caniko.cenix.fixture`, checks the list, launches it, then uninstalls
5. Sends `FORCE_NATIVE_FAILURE`, checks the banner, force-stops Cenix, checks the banner after restart
6. Installs a `-PomitNative` APK (no `libcenix_ffi.so`) and checks emergency HOME still searches

If the image, emulator binary, or boot fails, the script writes `docs/emulator-blocker.md` and exits non-zero.

## Host command set

```bash
nix develop --command scripts/check-bindings.sh
nix develop --command scripts/run-host-tests.sh
nix develop --command scripts/assemble-debug.sh
nix develop --command scripts/audit-apk.sh
nix develop --command scripts/check-16k.sh android/app/src/main/jniLibs/arm64-v8a/libcenix_ffi.so android/app/src/main/jniLibs/x86_64/libcenix_ffi.so
```
