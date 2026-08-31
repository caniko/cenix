# Shortcut and context reference

Reference: GrapheneOS `platform_packages_apps_Launcher3`, branch `17`, commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`, plus public Android launcher APIs.

| Behavior | Cenix boundary | Evidence |
| --- | --- | --- |
| Published shortcuts | `LauncherApps.getShortcuts`; manifest before dynamic, then rank and ID; at most four with two dynamic when available | `API`, `AOSP_EMU` |
| Durable identity | Package, shortcut ID, and profile only; Room v5 and typed reducer | host tests, migration instrumentation |
| Launch | `LauncherApps.startShortcut` for the matching profile | `AOSP_EMU` |
| Incoming pin | Valid `PinItemRequest`, explicit Add/Cancel, accept before durable placement | `AOSP_EMU` |
| Context actions | Transient Kotlin popup; app info and uninstall use Android surfaces | `AOSP_EMU` |
| Reconciliation | Shortcut callback resolves live enabled IDs, normalizes mixed folders, and synchronizes platform pins idempotently | host tests, `AOSP_EMU` |

Final P2B proof is clean commit `9ab9e1af37f9b90d6dc9d86b4f700a6e9e821856`. Five normal AOSP API 35 runs, forced RTL, and 1.3x font scale passed. No physical `mustang`, GrapheneOS, or TalkBack claim is made.
