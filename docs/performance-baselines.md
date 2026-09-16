# Performance baselines

## Customization qualification (pending device measurements)

`DrawerNavigationTest` checks focus routing into visible results, private exclusion and
unknown-category fallback. Section headings expose heading semantics; sections reuse the
same icon/status/dot binder as All Apps. These unit checks are not a TalkBack or frame-time pass.

Before approving performance budgets, record a fixed device/OS, refresh rate, thermal state,
app count, selected pack/catalog size and cold/warm cache conditions. Measure stock and Cenix
with animations enabled; measure Cenix-only category and icon-picker journeys separately.
Retain raw frame/startup traces and report sample counts and p95, rather than adopting the
previous unmeasured 20% / two-percentage-point proposals as passing results.

Manual gates: DPAD from search to sections/All Apps/private results and back; keyboard activation
and long-press actions; TalkBack headings, counts and selected chips; icon preview names and
search results; 200% text and RTL. Capture actual results on the signed ARM64 candidate.

Static only. No Macrobenchmark numbers in this tree.

`scripts/check-performance-static.sh` fails on:

- `INTERNET` permission
- production `allowMainThreadQueries`
- obvious persistent polling (`while (true)`, `Timer`, `scheduleAtFixedRate`) in app sources

Search debounce is 50ms on `cenix-io`. Room and workspace mutations stay off the main thread.

Internal drag motion, edge hover, pager animation, IME state, and rendering stay in Kotlin. UniFFI and Room are called once per accepted drop, not per pointer event (`API`).

Add device traces when a mustang or isolated emulator jank run exists.
