# Parity roadmap

P1 provides the typed durable workspace, dynamic pages, hotseat, internal drag, and deterministic reorder. P1.5 provides separate full-screen HOME and All Apps surfaces with swipe, Back, HOME-intent, IME, orientation, RTL, and large-font evidence. Folders, widgets, icon packs, dots, Private Space UI, and Quickstep stay out.

| Remaining | Class | Evidence needed |
| --- | --- | --- |
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
