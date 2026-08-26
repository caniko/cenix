# Parity roadmap

P1 provides the typed durable workspace, dynamic pages, hotseat, internal drag, and deterministic reorder. P1.5 provides separate full-screen HOME and All Apps surfaces with swipe, Back, HOME-intent, IME, orientation, RTL, and large-font evidence. P2A provides typed, Room-backed folders. P2B provides typed manifest/dynamic shortcuts, incoming pin confirmation, and transient app/shortcut context actions. P3 provides production app-widget hosting, pin/configuration/restore flows, resize, and emergency isolation. P4 provides work-profile parity and the public third-party HOME subset of Private Space. Icon packs, dots, and Quickstep stay out.

| Remaining | Class | Evidence needed |
| --- | --- | --- |
| Disabled / suspended chrome | `INSTALLABLE_PUBLIC` | `API` + `AOSP_EMU` |
| User-selected grid remap | `INSTALLABLE_PUBLIC` | later |
| TalkBack pass | `INSTALLABLE_PUBLIC` | `AOSP_EMU` then `GOS_DEV` |
| Icon packs | `INSTALLABLE_PUBLIC` | later |
| Notification dots | `USER_AUTHORIZED` | later |
| Wallpaper | `USER_AUTHORIZED` | later |
| Private Space settings/install/system animation parity | `SIGNATURE_OR_SYSTEM` | omitted |
| Recents / Quickstep | `SIGNATURE_OR_SYSTEM` | omitted |

Device proof is `GOS_DEV` on GrapheneOS `2026081300` / `mustang`. Emulator proof is never that.
