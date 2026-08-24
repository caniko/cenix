# GrapheneOS device lab

Contract only until a mustang is attached.

```bash
CENIX_GOS_SERIAL=<serial> scripts/grapheneos-device-conformance.sh
```

Requires:

- `adb` authorized
- `ro.product.device=mustang`
- GrapheneOS (checks `ro.grapheneos.version`)

Does not install APKs, does not claim parity, does not treat the AOSP emulator as this lab.

Target: Pixel 10 Pro XL, GrapheneOS `2026081300`. Outstanding: attach that device and compare stock Launcher vs Cenix.
