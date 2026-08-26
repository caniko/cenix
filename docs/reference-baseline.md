# GrapheneOS Launcher3 reference baseline

Inspected 2026-08-23. Source inventory, not a device observation, unless noted.

## Pin

| Field | Value |
| --- | --- |
| Remote | `https://github.com/GrapheneOS/platform_packages_apps_Launcher3` |
| Branch | `17` (HEAD of this branch at inspect time) |
| Commit | `e5fde8f4368539554b07ee136abd27bc7b4284c1` (`fixup! don't reserve space for quick search bar`, 2026-07-03) |
| GrapheneOS release | `2026081300` ([releases](https://grapheneos.org/releases#2026081300); Pixel 10 Pro XL / `mustang` is a supported target) |
| Android line | GrapheneOS Android 17 (release notes for `2026081300`) |
| Reference device | Pixel 10 Pro XL, codename `mustang` |
| Cenix compile/target SDK | 35 (nixpkgs androidenv composition; GrapheneOS 17 is newer) |

## Device observation

No `mustang` was attached. Build fingerprint, live density, and the grid the stock launcher picks on that panel are **unobserved**.

Emulator used for the vertical slice: AOSP API 35 `google_apis` x86_64 AVD `cenix-api35`, 320×640 px dump. That is not GrapheneOS and not `mustang`.

## Source inventory (commit above)

Manifests (`AndroidManifest.xml`, `AndroidManifest-common.xml`):

- `minSdkVersion` 30, `targetSdkVersion` 33
- HOME activity: `MAIN` + `HOME` + `DEFAULT` + `MONKEY` + `LAUNCHER_APP`, plus `SHOW_WORK_APPS` / `ALL_APPS`
- `QUERY_ALL_PACKAGES`, `BIND_APPWIDGET`, `SET_WALLPAPER`, `LOCK_APPS`, `POST_NOTIFICATIONS`, no `INTERNET`
- Recents/Quickstep overlay, notification listener, widget restore receiver, session-commit receiver
- `CONFIRM_PIN_SHORTCUT` / `CONFIRM_PIN_APPWIDGET`
- `LauncherProvider` (`ACCESS_LAUNCHER_DATA`), grid-control provider
- `SECONDARY_HOME` activity
- Organizer / folder-creator activities (not exported)
- App Functions service

Grids (`res/xml/device_profiles.xml`), phone category only for v1:

| Grid | Rows×cols | Hotseat | Notes |
| --- | --- | --- | --- |
| `2_by_2` | 2×2 | 2 | phone |
| `3_by_3` | 3×3 | 3 | phone |
| `4_by_4` | 4×4 | 4 | phone / multi_display |
| `4_by_5` | 5×4 | 4 | phone / multi_display; “Pixel 2021” 367×838 dps |
| `5_by_5` | 5×5 | 5 | phone / multi_display; “Large Phone” 406×694 dps |
| `6_by_5` | 5×6 | 6 | **tablet — out of v1** |
| `desktop_6_by_5` | 5×6 | 6 | **desktop — out of v1** |

GrapheneOS deltas visible on `17`: no reserved hotseat search bar; 4×5 grid; Private Space “Install in private” shortcut removed.

## Profiles

Source has work-profile and Private Space UI. Default user is personal. Cenix implements typed work chrome and the public third-party HOME Private Space boundary documented in `private-space-capability.md`.

## Product copy rule

Cenix copies user-visible HOME behavior where public APIs allow it. It does not copy AOSP Java. Quickstep/Recents parity is not claimed.
