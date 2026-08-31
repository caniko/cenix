# Parity roadmap

P1 through P4 provide the typed launcher, shell, folders, shortcuts, widgets, and profile boundary. P5A adds grid and package lifecycle settings. P5B adds user-authorized notification dots, automatic new-app placement, system wallpaper appearance, dynamic colors, and public adaptive themed icons. Icon packs and Quickstep stay out.

| Remaining | Class | Evidence needed |
| --- | --- | --- |
| Disabled / suspended chrome | `INSTALLABLE_PUBLIC` | `API` + `AOSP_EMU` |
| TalkBack pass | `INSTALLABLE_PUBLIC` | `AOSP_EMU` then `GOS_DEV` |
| Icon packs | `INTENTIONALLY_OMITTED` | not part of themed-icon scope |
| Private Space settings/install/system animation parity | `SIGNATURE_OR_SYSTEM` | omitted |
| Recents / Quickstep | `SIGNATURE_OR_SYSTEM` | omitted |
| Android backup | `TRANSPORT_CONTROLLED` | P5C local AOSP transport proven; external transport proof remains platform-dependent |
| Launcher layout backup/restore | `USER_AUTHORIZED` | P5C SAF export/import and fresh-AVD restore proven on AOSP |

Device proof is `GOS_DEV` on GrapheneOS `2026081300` / `mustang`. Emulator proof is never that.
