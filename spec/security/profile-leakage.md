# Profile leakage contract

When a profile is quiet, locked, inaccessible, or removed:

- application labels, icons, components, shortcut IDs, shortcut labels, widget providers, and widget views are not loaded or rendered
- pending search results are invalidated before replacement data can bind
- context popups, folders, and drag payloads are cancelled
- workspace state may expose only a generic unavailable placeholder for non-private profiles
- private workspace projection is hidden and new private placement is rejected
- release diagnostics redact keys containing package, class, label, query, profile, component, title, serial, user, shortcut, widget, or provider
- profile removal is the only profile lifecycle event that deletes durable rows

The emulator fixture uses `Cenix Auxiliary` and `com.caniko.cenix.fixture.secondary` as private sentinels. After lock, the `profiles` suite rejects either sentinel in UI, Cenix logcat, or the diagnostic ring.
