# Backup and restore parity contract

Evidence labels: `SRC`, `API`, `AOSP_EMU`, `GOS_DEV`, `SYSTEM`, `UNKNOWN`.
The source implementation has `SRC` + `API` evidence. AOSP local-transport and fresh-AVD SAF restore have `AOSP_EMU` evidence; do not treat them as external-transport or `GOS_DEV` proof.

## Domain

- Room is the only durable authority. P5C introduces schema v9 as an additive journal on top of v8 workspace tables.
- The canonical interchange object is one bounded JSON artifact. The UniFFI boundary remains typed records/enums; JSON, protobuf, and byte blobs do not cross FFI.
- Item identity is launcher `item_id`. Applications use package/class/profile serial. Shortcuts use package/shortcut ID/profile serial. Widgets use provider package/class/profile serial and spans. Android `appWidgetId` is Room-only.
- Personal and work are logical slots. Personal maps to the current personal profile. Manual work import requires explicit mapping to an available managed profile; automatic transport backup is personal-only by default. Private Space (`USER_TYPE_PROFILE_PRIVATE`) is never encoded or imported.
- Phone grids already selectable in settings remain the only grids. Tablet, foldable, and secondary-display layouts stay out.

## Lifecycle

1. Export reads committed Room rows, drops excluded fields, writes JSON, and may hand that file to `BackupAgent.onFullBackup` if the transport is enabled.
2. Transport success or failure is `TRANSPORT_CONTROLLED`. Quota is `FullBackupDataOutput.getQuota()`. Overflow calls `BackupAgent.onQuotaExceeded`; export then fails closed.
3. Import journals a bounded `PARSED` artifact. Validation, explicit profile mapping, and reducer planning run before any workspace mutation.
4. A single Room transaction applies the typed `WorkspaceTransition` at the expected generation and advances generation by one. Failure rolls back workspace and journal.
5. Widget rows commit as placeholders. `ACTION_APPWIDGET_HOST_RESTORED` is accepted only for the Cenix host with equal ID arrays. Because the canonical artifact contains no trusted old IDs, unmatched transport instances stay placeholders and are reclaimed by host recovery.
6. Missing applications and shortcuts stay removable placeholders until package callbacks reconcile or the user removes them. Temporary unavailability is not deletion.
7. `BackupAgent.onRestoreFinished` only marks the journal pending. Application happens on the next healthy, non-emergency start.

## Placeholders and widget rebind

- Placeholder policy matches existing widget restore: no `RemoteViews` until `AppWidgetManager.getAppWidgetInfo` is valid and the profile is available.
- Host mismatch, null arrays, or unequal old/new lengths discard the broadcast.
- Providers that need configuration stay in a setup-required placeholder (`FLAG_UI_NOT_READY` pattern). Unbound host IDs are reclaimed without treating artifact data as an `appWidgetId` mapping.

## Emergency

Emergency HOME does not parse JSON, call UniFFI, start host listening for restored widgets, or mutate workspace from backup. Rows remain for retry-native or user reset.

## Corruption

Reject the entire import, leave the prior generation unchanged, when any of these hold:

- oversize vs quota or vs the parser's finite caps
- invalid UTF-8, truncated JSON, extra unknown required structure, or schema version mismatch
- overlap, zero/negative spans, hotseat widgets, mixed-profile folders
- private serials or private user type
- work items without an explicit valid target mapping, unless the user explicitly omits that work subset before confirmation
- stale workspace generation
- journal already committing from a different artifact

Do not merge, skip-bad-rows, or auto-repair into a partial home screen.

## Exclusions

Incremental key/value backup, raw database restore, file-system items, custom widgets, app groups, QSB, Recents/Quickstep, organizer blobs, `BackupRestoreEventLogger`, `ACCESS_LAUNCHER_DATA`, notification content, icon packs, internet, and Private Space workspace items.

## Evidence boundary

| Claim | Evidence |
| --- | --- |
| Launcher3 agent/restore/widget-restore behavior | `SRC` |
| Public backup and widget restore APIs | `API` |
| Quickstep restore logger is SystemApi | `SRC`, `SYSTEM` |
| Cenix P5C source/build implementation | `SRC`, `API` |
| Isolated emulator local-transport and SAF restore | `AOSP_EMU` |
| GrapheneOS mustang / Seedvault | no `GOS_DEV`; transport is `UNKNOWN` |
