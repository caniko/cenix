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
| `EMERGENCY` | persisted or requested |
| `RESET` | reset-local-state |
| `HOME_ROLE` | reserved |

Release/dogfood set `BuildConfig.REDACT_LOGS=true`. Keys containing `package`, `class`, `label`, `query`, `profile`, or `component` become `[redacted]`.

Long-press the status title → SAF `CreateDocument` export. Header is `version`, `buildType`, `commit`, `schema`. Export is user-triggered only.
