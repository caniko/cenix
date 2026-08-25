# Parity roadmap

P1 provides the typed durable workspace, dynamic pages, hotseat, internal drag, and deterministic reorder. P1.5 replaces the stacked conformance screen with separate HOME and All Apps surfaces. Folders, widgets, icon packs, dots, Private Space UI, and Quickstep stay out.

| Next | Class | Evidence needed |
| --- | --- | --- |
| Production HOME and All Apps shell | `INSTALLABLE_PUBLIC` | `API` + `AOSP_EMU` |
| Swipe and Back shell transitions | `INSTALLABLE_PUBLIC` | `API` + `AOSP_EMU` |
| Responsive portrait/landscape workspace | `INSTALLABLE_PUBLIC` | `AOSP_EMU` then `GOS_DEV` |
| Disabled / suspended chrome | `INSTALLABLE_PUBLIC` | `API` + `AOSP_EMU` |
| Work-profile chrome | `INSTALLABLE_PUBLIC` | `API` + `AOSP_EMU` |
| User-selected grid remap | `INSTALLABLE_PUBLIC` | later |
| TalkBack pass | `INSTALLABLE_PUBLIC` | `AOSP_EMU` then `GOS_DEV` |
| Folders | `INSTALLABLE_PUBLIC` | later |
| Widgets / pin confirm | `USER_AUTHORIZED` | later |
| Icon packs | `INSTALLABLE_PUBLIC` | later |
| Notification dots | `USER_AUTHORIZED` | later |
| Wallpaper | `USER_AUTHORIZED` | later |
| Private Space | `UNKNOWN_REQUIRES_SPIKE` | omitted |
| Recents / Quickstep | `SIGNATURE_OR_SYSTEM` | omitted |

Device proof is `GOS_DEV` on GrapheneOS `2026081300` / `mustang`. Emulator proof is never that.
