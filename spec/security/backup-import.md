# Backup import security boundary

P5C contract only. Evidence: `SRC`, `API`. No implementation, `AOSP_EMU`, or `GOS_DEV` claim.

## Trust boundary

- The backup transport (`BackupAgent.onFullBackup` / restore file delivery) is `TRANSPORT_CONTROLLED`. Cenix does not assume cloud, D2D, or GrapheneOS Seedvault semantics.
- A user-authorized local import, if added, is the same parser and transaction as transport restore. There is no silent path.
- `BackupManager.requestRestore` is not used.
- HOME role is not authority to read another app's backup. Private Space discovery remains `ROLE_HOME_GATED` and is used only to refuse private payloads.

## Allowed input

JSON may contain logical personal/work layout: pages, cells, spans, folder titles/order, application component IDs, shortcut IDs, widget provider IDs, and the existing settings booleans. It contains logical backup-profile references, not source Android user IDs, serials, or `UserHandle` values.

## Forbidden input and side channels

Import must not accept or persist:

- Private Space serials, `USER_TYPE_PROFILE_PRIVATE`, private package/class/shortcut/widget identities
- notification keys, titles, text, people, URIs, or counts
- labels, icons, bitmaps, `RemoteViews`, intents except the typed component/shortcut/provider IDs above
- file URIs or filesystem item types (`WorkspaceItemProcessor.processFileSystemItem` reference: unrestorable)
- emergency flags, crash-loop counters, diagnostic rings, logs
- `appWidgetId` as a trusted identity from the artifact (platform remap only)
- extra Room tables, SQL, or native blobs

Unmapped work items block confirmation unless the user explicitly omits the work subset. They are never parked in personal space. Personal and work never collapse into one target profile.

## Mapping

- Logical personal → current `UserManager.getSerialNumberForUser` for `Process.myUserHandle`.
- Logical work → an explicitly selected currently available managed profile for manual import. Automatic transport backup excludes work identities by default.
- Any private classification → reject the artifact.

## Parser and corruption

Fail closed before Room mutation:

- size above `FullBackupDataOutput.getQuota()` or above finite parser caps
- invalid UTF-8 / JSON / schema version
- unknown item kinds, overlapping cells, widgets in hotseat or folders
- mixed-profile folders
- private identities

One Room v9 transaction. No partial apply, no skip-and-continue.

## Runtime isolation

- Emergency mode: do not parse, do not call UniFFI, do not inflate widgets, do not write import journals forward. Preserve existing rows.
- Quiet/locked/unavailable profiles: do not load labels, icons, shortcuts, or widget views for those serials; generic placeholders only for non-private rows (existing profile-leakage contract).
- Release logs and diagnostics redact package, class, label, profile, serial, shortcut, widget, and provider keys from import failures.

## Transport limits

`onQuotaExceeded(backupDataBytes, quotaBytes)` aborts export. `FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED` and `FLAG_DEVICE_TO_DEVICE_TRANSFER` are transport flags to log as booleans only, not as a reason to widen scope. Deprecated `requestRestore` stays unused.
