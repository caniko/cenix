# Performance baselines

Static only. No Macrobenchmark numbers in this tree.

`scripts/check-performance-static.sh` fails on:

- `INTERNET` permission
- production `allowMainThreadQueries`
- obvious persistent polling (`while (true)`, `Timer`, `scheduleAtFixedRate`) in app sources

Search debounce is 50ms on `cenix-io`. Room and workspace mutations stay off the main thread.

Internal drag motion, edge hover, pager animation, IME state, and rendering stay in Kotlin. UniFFI and Room are called once per accepted drop, not per pointer event (`API`).

Add device traces when a mustang or isolated emulator jank run exists.
