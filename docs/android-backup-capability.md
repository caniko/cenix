# Android backup capability

Public API 35 plus Launcher3 `e5fde8f4368539554b07ee136abd27bc7b4284c1`. Source presence is not user-visible proof. No `AOSP_EMU` or `GOS_DEV` claim is made.

## Public boundary

API 35 `android.jar` exposes:

- `android.app.backup.BackupAgent` — `onBackup`, `onRestore`, `onFullBackup`, `onQuotaExceeded`, `fullBackupFile`, `onRestoreFile`, `onRestoreFinished`; flags `FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED`, `FLAG_DEVICE_TO_DEVICE_TRANSFER`
- `android.app.backup.FullBackupDataOutput` — `getQuota()`, `getTransportFlags()`
- `android.app.backup.BackupManager` — `dataChanged()`, deprecated `requestRestore(RestoreObserver)`, `getUserForAncestralSerialNumber(long)`
- Manifest: `android:allowBackup`, `android:backupAgent`, `android:fullBackupOnly`, `android:fullBackupContent`, `android:backupInForeground`, `android:restoreAnyVersion`
- Widgets: `AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED`, `EXTRA_HOST_ID`, `EXTRA_APPWIDGET_OLD_IDS`, `EXTRA_APPWIDGET_IDS`; provider-side `ACTION_APPWIDGET_RESTORED`
- Profiles: `UserManager.getSerialNumberForUser`, `getUserForSerialNumber`, `USER_TYPE_PROFILE_MANAGED`, `USER_TYPE_PROFILE_PRIVATE`; HOME-gated `ACCESS_HIDDEN_PROFILES` / `LauncherApps.getLauncherUserInfo`

Any installable app may declare a `BackupAgent`. Whether a payload moves is the backup transport and the user, not the HOME role.

## Classification

| Capability | Class | Evidence |
| --- | --- | --- |
| Declare `BackupAgent` / `allowBackup` / include XML | `INSTALLABLE_PUBLIC` | `API` |
| Full-backup file payload + quota callback | `TRANSPORT_CONTROLLED` | `API` |
| Incremental `onBackup` / `onRestore` records | `INTENTIONALLY_OMITTED` | `SRC`, `API` |
| `BackupManager.dataChanged` | `INSTALLABLE_PUBLIC` | `API` |
| `BackupManager.requestRestore` | `INTENTIONALLY_OMITTED` | `API` |
| `getUserForAncestralSerialNumber` work mapping | `TRANSPORT_CONTROLLED` | `API`, `SRC` |
| Widget host ID remap broadcast | `USER_AUTHORIZED` | `API`, `SRC` |
| Widget bind / configure after remap | `USER_AUTHORIZED` | `API` |
| Private Space discovery during restore | `ROLE_HOME_GATED` | `API` |
| Persist or import Private Space items | `INTENTIONALLY_OMITTED` | `SRC`, `API` |
| `BackupRestoreEventLogger` (Quickstep) | `SIGNATURE_OR_SYSTEM` | `SRC`, `SYSTEM` |
| `ACCESS_LAUNCHER_DATA` / `LauncherProvider` | `SIGNATURE_OR_SYSTEM` | `SRC`, `SYSTEM` |
| Organizer blob import/export | `INTENTIONALLY_OMITTED` | `SRC` |
| Custom widgets, file-system items, app groups | `INTENTIONALLY_OMITTED` | `SRC` |
| Cloud vs D2D vs GrapheneOS transport behavior | `TRANSPORT_CONTROLLED` | `UNKNOWN` |
| Exact GrapheneOS `mustang` backup UX | `UNKNOWN_REQUIRES_SPIKE` | `UNKNOWN` |

## Launcher3 pattern (not to copy)

`LauncherBackupAgent` skips incremental backup, restores included files, then sets `RestoreDbTask` pending. First launch sanitizes favorites: drop unrestored profiles, remap personal/work serials, mark items restored, remap widget IDs if the host broadcast arrived, collapse single-display screen gaps. File-system items that arrive with a restore flag are deleted. `backupscheme.xml` includes grid DBs and launcher prefs; `LauncherFiles.BACKUP_DB`, icon caches, and device prefs are not in that include list.

## Cenix P5C use of the public boundary

- Transport carries one bounded JSON artifact, not SQLite.
- Kotlin parses and maps profiles; Rust sees only typed snapshots/commands.
- Personal and work are logical slots. Manual work import requires explicit mapping; automatic transport backup is personal-only by default. Private is rejected.
- Widget IDs rebind only through the public host-restored broadcast and the existing Room widget journal.
- Emergency and native-absent HOME never parse restore input.

The manifest now declares the custom full-backup agent and extraction rules for only `files/transport/cenix-backup.json`. This is source/build evidence only; transport behavior remains unclaimed until emulator and device execution.
