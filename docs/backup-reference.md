# Backup and restore reference

Clean-room behavioral inventory. Reference pin: GrapheneOS `platform_packages_apps_Launcher3` commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`. Public names from Android 35 `android.jar`. Cenix does not copy Launcher3 database, agent, or restore-task code.

This document is the P5C contract. Local-transport and SAF execution have isolated AOSP emulator evidence; external transport and physical-device behavior remain unproven.

## P5C scope

Include only durable launcher layout that Room already owns:

- workspace pages, ranks, cells, and spans
- hotseat occupancy
- folders and ordered members
- application items by package, class, and logical backup-profile reference
- pinned shortcuts by package, shortcut ID, and logical backup-profile reference
- widgets by provider package, class, logical backup-profile reference, and spans
- launcher settings already in Room (`gridName`, notification-dots preference, themed-icons preference, auto-add preference)

Exclude:

- raw `cenix.db` / Launcher3 favorites SQLite
- live Android `appWidgetId` as canonical identity
- `UserHandle`, user IDs, labels, icons, `RemoteViews`
- notification payloads or badge counts
- Private Space identities and private workspace rows
- emergency / crash-loop metadata
- icon caches, widget previews, logs, diagnostics
- file-system shortcuts, custom widgets, app pairs, QSB, Recents
- organizer blob layout (`LayoutImportExportHelper`)
- internet, sync, or a second durable store

## Reference vs Cenix boundary

| Behavior | Reference path | Cenix P5C boundary | Class | Evidence |
| --- | --- | --- | --- | --- |
| Agent registration | `AndroidManifest.xml` / `AndroidManifest-common.xml` `android:backupAgent`, `android:fullBackupOnly`, `android:fullBackupContent` | Public `BackupAgent` emits/consumes one bounded JSON artifact; never full-backup Room | `INSTALLABLE_PUBLIC` | `SRC`, `API` |
| Include list | `res/xml/backupscheme.xml` databases + `com.android.launcher3.prefs.xml` + `downgrade_schema.json` | Explicit include of the JSON artifact only; caches and `backup.db` stay out | `INSTALLABLE_PUBLIC` | `SRC` |
| Incremental KV | `src/com/android/launcher3/LauncherBackupAgent.java` empty `onBackup` / `onRestore` | Same: no key/value records | `INTENTIONALLY_OMITTED` | `SRC`, `API` |
| Full backup | `BackupAgent.onFullBackup(FullBackupDataOutput)` | Honor `getQuota()` / `getTransportFlags()`; abort on `onQuotaExceeded` | `TRANSPORT_CONTROLLED` | `API` |
| Restore complete | `LauncherBackupAgent.onRestoreFinished` → `RestoreDbTask.setPending` | Journal pending import in Room v9; apply on first healthy start, not inside the agent | `INSTALLABLE_PUBLIC` | `SRC` |
| Deferred sanitize | `src/com/android/launcher3/provider/RestoreDbTask.kt` | Typed UniFFI plan + one Room transaction; no copied SQL sanitize | `INSTALLABLE_PUBLIC` | `SRC` |
| Personal serial | `RestoreDbTask.sanitizeDB` default `profileId` → current `Process.myUserHandle` serial | Logical `personal` slot → current personal serial | `INSTALLABLE_PUBLIC` | `SRC`, `API` |
| Work serial | `BackupManager.getUserForAncestralSerialNumber` | Manual import requires an explicit logical `work` → available managed-profile mapping; transport backup excludes work identities by default | `TRANSPORT_CONTROLLED` | `SRC`, `API` |
| Unrestored profiles | delete rows whose `profileId` is not mapped | Block confirmation or explicitly omit the work subset; never map it to personal | `INSTALLABLE_PUBLIC` | `SRC` |
| Private Space | no restore mapping in `RestoreDbTask`; Cenix already rejects private workspace rows | Reject any private serial or `USER_TYPE_PROFILE_PRIVATE` payload | `ROLE_HOME_GATED` | `SRC`, `API` |
| Widget host restore | `AppWidgetsRestoredReceiver.java`; `ACTION_APPWIDGET_HOST_RESTORED` | Accept only Cenix host ID and equal arrays; canonical transport has no trusted old IDs, so unmatched instances remain placeholders and host recovery reclaims their IDs | `USER_AUTHORIZED` | `SRC`, `API` |
| Widget flags | `LauncherAppWidgetInfo.FLAG_ID_NOT_VALID`, `FLAG_PROVIDER_NOT_READY`, `FLAG_UI_NOT_READY` | Removable placeholder until bind/setup; no fabricated `RemoteViews` | `USER_AUTHORIZED` | `SRC`, `API` |
| File items | `WorkspaceItemProcessor.processFileSystemItem` deletes restored file items | Never encode file URIs | `INTENTIONALLY_OMITTED` | `SRC` |
| Grid copy table | `src/com/android/launcher3/model/GridBackupTable.java` | Not used; JSON + Room v9 journal replace in-DB table copies | `INTENTIONALLY_OMITTED` | `SRC` |
| Restore metrics | `quickstep/.../LauncherRestoreEventLoggerImpl.kt` (`BackupRestoreEventLogger`, SystemApi) | Do not call | `SIGNATURE_OR_SYSTEM` | `SRC`, `SYSTEM` |
| Organizer layout blobs | `src/com/android/launcher3/util/LayoutImportExportHelper.kt` | Do not call | `INTENTIONALLY_OMITTED` | `SRC` |
| `BackupManager.requestRestore` | public, `@Deprecated` | Do not call | `INTENTIONALLY_OMITTED` | `API` |
| Transport / Seedvault / cloud | not in Launcher3 source as a Cenix-owned API | User and OEM transport; GrapheneOS behavior unobserved | `TRANSPORT_CONTROLLED` | `UNKNOWN` |
| Device proof | — | Local AOSP transport and fresh-AVD SAF restore passed; no mustang run exists | — | `AOSP_EMU`, no `GOS_DEV` |

## Artifact and UniFFI

The on-disk / transport object is one UTF-8 JSON document with a finite schema version, finite arrays, and finite string lengths. Size must be below `FullBackupDataOutput.getQuota()`.

UniFFI stays typed (`WorkspaceSnapshot`, `WorkspaceCommand`, `WorkspaceTransition`, `ProfileDescriptor`). JSON is parsed in Kotlin and never crosses the FFI as a string or byte blob.

`appWidgetId` remains Room-only, keyed by launcher `item_id`.

## Room v9

Current durable schema in this tree is Room v9:

- existing workspace tables remain the only committed authority
- one bounded restore journal records `PARSED`, `USER_CONFIRMED`, `SYSTEM_RESTORE_PENDING`, `APPLYING`, `PLATFORM_RECONCILE`, or `FAILED`
- expected workspace generation; replacement advances it exactly once; Room rollback prevents partial workspace state
- migration 8→9 is additive and non-destructive

## Placeholders, remap, emergency, corruption

Missing applications or shortcuts keep a removable placeholder. Missing or unbound widgets keep a removable placeholder (`PendingAppWidgetHostView` pattern in `src/com/android/launcher3/widget/PendingAppWidgetHostView.java`).

`ACTION_APPWIDGET_HOST_RESTORED` remaps IDs only for the Cenix host (`WidgetHostController.HOST_ID` in this tree; Launcher3 uses `LauncherWidgetHolder.APPWIDGET_HOST_ID = 1024`). Unequal arrays, wrong host, or a non-pending journal are ignored. Unmapped new IDs are deleted through `AppWidgetHost.deleteAppWidgetId`.

Emergency mode does not parse artifacts, call UniFFI, or inflate restored widgets. Durable rows stay for retry or reset.

Corrupt, oversized, unknown-kind, overlapping, private, or generation-mismatched input is rejected as a whole. The previous Room generation is unchanged.
