# Folder reference

Reference: GrapheneOS `platform_packages_apps_Launcher3`, branch `17`, commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`, corresponding to build `2026081300`.

This is a clean-room behavioral inventory. Cenix does not copy Launcher3 implementation code.

| Behavior | Reference evidence | Level | P2A decision |
| --- | --- | --- | --- |
| Creation activation | `Workspace.willCreateUserFolder` uses the target cell's central folder-creation radius | `SRC`, `API` | Central 50% target region; leaving it cancels folder intent |
| Creation hover | Folder feedback starts when the pointer enters the activation radius; occupied-cell reorder has a separate 650 ms alarm | `SRC` | Preview immediately; ordinary reorder remains the fallback outside the region |
| Creation animation | `FolderIcon` animates destination then source into the new icon; drop-in duration is 400 ms | `SRC`, `API` | Native View animation, bounded to 220 ms |
| Initial member order | Destination item is added first, dragged source second | `SRC` | Ranks 0 and 1 respectively |
| Default title | New `FolderInfo` starts unlabeled; asynchronous suggestions may later assign a title | `SRC` | Persist empty title and expose localized “Folder” as the safe display name |
| Icon preview | Ranked first-page members; maximum preview count is four | `SRC` | First four members by rank |
| Open / close | Icon click opens; another open folder closes; Back and outside tap close | `SRC`, `API` | One transient popup; Back closes IME before popup; outside tap and HOME close |
| Rename | Empty title is a distinct accepted state; editor commits only changed text | `SRC`, `API` | Empty allowed; Unicode up to 80 code points; controls rejected |
| Closed-folder add | Folder icon accepts applications while closed and can spring-open after 800 ms | `SRC`, `API` | Closed-folder drop adds at the end; 800 ms hover opens for an explicit open-folder drop |
| Open-folder add and reorder | Folder is a drop target; member reorder alarm is 250 ms | `SRC`, `API` | Semantic drop commits add/reorder; pointer motion remains Kotlin-only |
| Drag member out | Member is removed during drag and restored on failed drop | `SRC` | Durable state changes only on successful semantic drop, so cancel is a no-op |
| Dissolution | A folder with zero or one member is removed; the final member takes the folder placement | `SRC` | Same behavior in one Rust/Room transaction |
| Move folder / hotseat | Folder is a normal workspace item and uses normal placement paths, including hotseat placement | `SRC`, `API` | Existing move/reorder path handles folder icons |
| Package removal | Folder contents are model items and are reconciled when package activities disappear | `SRC`, `API` | `LauncherApps` live set drives transactional cleanup and dissolution |
| Disable / suspension | Source has package-state handling, but exact placeholder behavior was not established from this folder path | `UNKNOWN` | Preserve current Cenix policy: absent launchable activity is removed |
| Profile unavailable | Exact folder placeholder behavior was not established | `UNKNOWN` | Preserve current Cenix policy: unavailable launchables are removed |
| Process recreation | Folder contents are database-backed; open state is a floating View | `SRC`, `API` | Persist model only; popup always starts closed |
| Rotation | Source recreates/rebinds folder Views; exact open-popup retention was not proven on a device | `UNKNOWN` | Close popup on recreation; durable contents remain |
| RTL | Folder grid, title sizing, preview, and focus paths include RTL handling | `SRC`, `API` | Logical member ranks remain stable; visual columns mirror |
| Accessibility | Folder drop helper and semantic create/add/move descriptions are present | `SRC`, `API` | Explicit open, close, rename, move, remove, and member actions |
| Cross-profile membership | No explicit same-user or cross-user admission rule was found in creation/drop paths | `UNKNOWN` | Same-profile folders only; mixed-profile commands are rejected |

`AOSP_EMU` evidence is recorded only after clean committed Cenix conformance runs. No `GOS_DEV` evidence is claimed because no physical `mustang` is attached.
