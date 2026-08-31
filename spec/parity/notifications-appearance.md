# Notification and appearance invariants

1. Room schema v8 adds only `notificationDots`, `themedIcons`, and `autoAddApps` to the singleton launcher settings row.
2. Migration v7 to v8 preserves grid, workspace, folders, shortcuts, widgets, profiles, generation, and allocators.
3. Notification state is callback-driven, bounded, transient, package/profile scoped, and absent from Room, logs, diagnostics, and UniFFI.
4. Authorization, wallpaper selection, notification ranking, dynamic colors, adaptive masks, and user badging remain Android-owned public API behavior.
5. Profile lock, quiet mode, unavailability, removal, reset, emergency mode, listener disconnect, and authorization loss clear affected dots and inaccessible icon resources.
6. Themed icons default off and safely retain the original icon when no public monochrome layer exists.
7. Automatic placement defaults on, never targets private profiles, ignores failed/non-launchable sessions, and never creates a duplicate package/profile icon.
8. All workspace mutations continue through typed generated UniFFI commands and transactional Room commits.
9. Wallpaper data and appearance palettes are never persisted.
10. Notification content is never rendered in Cenix context popups.
