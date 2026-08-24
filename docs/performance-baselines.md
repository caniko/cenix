# Performance baselines

Static only. No Macrobenchmark numbers in this tree.

`scripts/check-performance-static.sh` fails on:

- `INTERNET` permission
- production `allowMainThreadQueries`
- obvious persistent polling (`while (true)`, `Timer`, `scheduleAtFixedRate`) in app sources

Search debounce is 50ms on `cenix-io`. Room and workspace mutations stay off the main thread.

Add device traces when a mustang or isolated emulator jank run exists.
