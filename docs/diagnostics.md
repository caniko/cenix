# Diagnostics

Events go to logcat tag `cenix` and a 64KiB × 4 file ring under `filesDir/diag/`.

| EventId | When |
| --- | --- |
| `STARTUP` | process start after Room |
| `BUILD_IDENTITY` | commit + build type |
| `ROOM_OPEN` | DB open / fail |
| `NATIVE_INIT` | retry-native result |
| `CATALOG_REFRESH` | LauncherApps reload |
| `PACKAGE_CALLBACK` | package add/remove/change |
| `WORKSPACE_TX` | pin/dock/place/remove |
| `CONTEXT_POPUP_OPEN` / `CONTEXT_POPUP_CLOSE` | transient context lifecycle |
| `SHORTCUT_QUERY` / `SHORTCUT_LAUNCH` / `SHORTCUT_PIN` / `SHORTCUT_RECONCILE` | shortcut platform boundary |
| `PLATFORM_ACTION` | app info and uninstall handoff result |
| `SHELL_TRANSITION` | HOME / All Apps swipe |
| `EMERGENCY` | persisted or requested |
| `RESET` | reset-local-state |
| `HOME_ROLE` | reserved |

Release/dogfood set `BuildConfig.REDACT_LOGS=true`. Keys containing `package`, `class`, `label`, `query`, `profile`, `component`, or `title` become `[redacted]`.

The HOME root's "Export diagnostics" accessibility action opens a SAF `CreateDocument` export. Header is `version`, `buildType`, `commit`, `schema`. Export is user-triggered only.
