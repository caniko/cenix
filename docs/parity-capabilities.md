# Parity capabilities

Classifications use public Android 35 APIs plus GrapheneOS Launcher3 `e5fde8f4368539554b07ee136abd27bc7b4284c1`. Source presence is not user-visible proof.

Evidence keys: `SRC` = Launcher3 source, `API` = public SDK, `AOSP_EMU` = isolated AOSP emulator, `GOS_DEV` = mustang (none), `SYSTEM` = privileged.

| Feature | Class | Slice | Evidence |
| --- | --- | --- | --- |
| HOME registration | `INSTALLABLE_PUBLIC` | done | `src`+`api`+`emu` |
| HOME role selection | `ROLE_HOME_GATED` | done | `RoleManager` / `cmd role`; `emu` |
| Workspace | `INSTALLABLE_PUBLIC` | done | dynamic pages; internal drag and reorder; `emu` |
| Hotseat | `INSTALLABLE_PUBLIC` | done | one row, `cols` slots; no QSB; drag |
| HOME / All Apps shell | `INSTALLABLE_PUBLIC` | done | separate surfaces; swipe, Back, HOME intent; `emu` |
| Local label search | `INSTALLABLE_PUBLIC` | done | `emu` |
| Folders | `INSTALLABLE_PUBLIC` | done | typed create, append, reorder, rename, extract, dissolve; `emu` |
| Drag and drop | `INSTALLABLE_PUBLIC` | done | Kotlin-only All Apps pin, move, dock, reorder, folder, remove; no swap |
| App/shortcut context actions | `INSTALLABLE_PUBLIC` | done | transient popup; app info, Android uninstall confirmation, drag/remove; `emu` |
| Widgets | `USER_AUTHORIZED` | done | real `AppWidgetHostView`, updates, resize, duplicate instances; `api`+`emu` |
| Widget restore / ID remap | `USER_AUTHORIZED` | done | `APPWIDGET_HOST_RESTORED`; journaled Room remap; `src`+`api`+`emu` |
| Pinned shortcuts | `USER_AUTHORIZED` | done | `LauncherApps`, typed Room state, `CONFIRM_PIN_SHORTCUT`; `emu` |
| Pinned-widget confirmation | `USER_AUTHORIZED` | done | explicit Add/Cancel through `CONFIRM_PIN_APPWIDGET`; `api`+`emu` |
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
