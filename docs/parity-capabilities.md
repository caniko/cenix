# Parity capabilities

Classifications use public Android 35 APIs plus GrapheneOS Launcher3 `e5fde8f4368539554b07ee136abd27bc7b4284c1`. Source presence is not user-visible proof.

Evidence keys: `src` = Launcher3 source, `api` = public SDK, `emu` = AOSP emulator slice, `dev` = mustang (none).

| Feature | Class | Slice | Evidence |
| --- | --- | --- | --- |
| HOME registration | `INSTALLABLE_PUBLIC` | done | `src`+`api`+`emu` |
| HOME role selection | `ROLE_HOME_GATED` | done | `RoleManager` / `cmd role`; `emu` |
| Workspace | `INSTALLABLE_PUBLIC` | done | one page; long-press pin; no drag |
| Hotseat | `INSTALLABLE_PUBLIC` | later | `src`; GrapheneOS removed hotseat QSB |
| All Apps list | `INSTALLABLE_PUBLIC` | done | `LauncherApps`; `emu` |
| Local label search | `INSTALLABLE_PUBLIC` | done | `emu` |
| Folders | `INSTALLABLE_PUBLIC` | later | `src` |
| Drag and drop | `INSTALLABLE_PUBLIC` | later | `src` |
| Widgets | `USER_AUTHORIZED` | later | `AppWidgetHost`; pin confirm is public |
| Widget restore / ID remap | `USER_AUTHORIZED` | later | `APPWIDGET_HOST_RESTORED`; `src` |
| Pinned shortcuts | `USER_AUTHORIZED` | later | `ShortcutManager` / `CONFIRM_PIN_SHORTCUT` |
| Pinned-widget confirmation | `USER_AUTHORIZED` | later | `CONFIRM_PIN_APPWIDGET` |
| Notification dots | `USER_AUTHORIZED` | later | `NotificationListenerService` |
| Package add/update/remove | `INSTALLABLE_PUBLIC` | done | `LauncherApps.Callback`; `emu` |
| Session commit | `INSTALLABLE_PUBLIC` | later | `SESSION_COMMITTED`; `src` |
| Disabled / suspended apps | `INSTALLABLE_PUBLIC` | partial | callbacks exist; no dedicated UI |
| Work profiles | `INSTALLABLE_PUBLIC` | partial | `LauncherApps.profiles`; no work chrome |
| Private Space | `UNKNOWN_REQUIRES_SPIKE` | omitted | `src` UI; public API surface unclear for 3p HOME |
| App locking | `SIGNATURE_OR_SYSTEM` | omitted | `LOCK_APPS` in `src` |
| Wallpaper / dynamic colors | `USER_AUTHORIZED` | later | `SET_WALLPAPER`; WallpaperColors |
| Grid migration | `INSTALLABLE_PUBLIC` | later | out-of-grid pins kept, not remapped |
| Launcher backup | `INSTALLABLE_PUBLIC` | omitted for now | `src` backup agent; Cenix `allowBackup=false` |
| Accessibility | `INSTALLABLE_PUBLIC` | partial | content descriptions; no TalkBack pass |
| Secondary display | `INSTALLABLE_PUBLIC` | omitted | `SECONDARY_HOME`; not phone v1 |
| Recents / Quickstep | `SIGNATURE_OR_SYSTEM` | omitted | overlay / privileged; do not claim |
| Launcher data provider | `SIGNATURE_OR_SYSTEM` | omitted | `ACCESS_LAUNCHER_DATA` |
| Organizer / folder creator | `INTENTIONALLY_OMITTED` | — | internal `src` activities |
| App Functions | `UNKNOWN_REQUIRES_SPIKE` | omitted | `src` service |
| Icon packs / Cuscon | `INSTALLABLE_PUBLIC` | later | public resources only; no Cuscon assets |
| `QUERY_ALL_PACKAGES` | `INTENTIONALLY_OMITTED` | — | Cenix uses `<queries>` MAIN+LAUNCHER |
| Internet / WebView / JS runtime | `INTENTIONALLY_OMITTED` | — | forbidden |

v1 product scope: Pixel phone layouts only. Tablet/desktop grids stay out.
