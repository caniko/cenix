# Widget parity contract

Evidence labels are `SRC`, `API`, `AOSP_EMU`, `GOS_DEV`, and `UNKNOWN`.

## Domain contract

- A widget is a typed launcher item identified in Rust by provider package, provider class, and profile.
- Android `appWidgetId` is platform state stored only in Room, keyed by launcher `item_id`; it never crosses UniFFI.
- Multiple instances of one provider are valid because launcher item IDs, not provider identity, define uniqueness.
- Widgets occupy rectangular workspace spans, may not overlap another item, and may not be placed in the hotseat or a folder.
- Move and resize are deterministic reducer commands. Resize preserves item identity and rejects zero, out-of-grid, overlapping, or provider-disallowed spans.
- Room is the only durable authority. Kotlin owns `AppWidgetHost`, `AppWidgetHostView`, provider metadata, activity results, and transient resize/picker state.

## Lifecycle contract

- Addition journals allocation, binding, configuration, platform acceptance, and durable commit boundaries.
- Cancellation or failure deletes uncommitted allocated IDs and clears the journal; recovery is idempotent after process death.
- A committed row with a valid binding renders a real `AppWidgetHostView` and receives provider updates while the launcher is visible.
- A missing provider, invalid binding, or incomplete restore renders a removable placeholder and does not corrupt unrelated workspace state.
- Restore broadcasts are accepted only for Cenix's stable host ID with equal old/new ID arrays and are applied transactionally through the journal.
- Incoming pin requests require explicit Add/Cancel confirmation. Cancellation writes nothing and acceptance happens only when a placement can commit.
- Emergency mode never interprets or proxies `RemoteViews`; it isolates widget hosting while preserving durable rows for retry or removal.

## Scope

P3 excludes custom widgets, dots, work-profile UI, Private Space, icon packs, backup import/export UI, secondary displays, tablets/foldables, Quickstep, feeds, weather, web search, AI, sync, plugins, and TalkBack certification.

The production contract passed five isolated full AOSP API 35 runs plus forced RTL and font scale 1.3 at implementation commit `735286c011cf2559bc31d5a7aaf1f277d6ef5e7c`. This is emulator evidence, not GrapheneOS device evidence.
