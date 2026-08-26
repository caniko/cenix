# GrapheneOS device lab

Contract only until a mustang is attached.

```bash
CENIX_GOS_SERIAL=<serial> CENIX_GOS_VERSION=<verified-version> scripts/grapheneos-device-conformance.sh
```

Requires:

- `adb` authorized
- `ro.product.device=mustang`
- GrapheneOS (checks `ro.grapheneos.version`)
- an independently supplied expected GrapheneOS version matching the device property

Does not install APKs, does not claim parity, does not treat the AOSP emulator as this lab.

Target: Pixel 10 Pro XL, GrapheneOS `2026081300`. Outstanding: attach that device and compare stock Launcher vs Cenix.

P5A operator checklist: settings entry and recreation, every compatible finite grid, shrink/expand/repeat/cancel/cold boot, widget minimum-span blocker, installer progress, update, suspension, disablement, archive/restore, temporary unavailability, permanent per-profile removal, work/private isolation, forced RTL, and font scale 1.3. Archive and private-space rows are capability-gated when the physical build cannot create them through public user-visible flows.
