# Profile reference

Reference: GrapheneOS `platform_packages_apps_Launcher3`, branch `17`, commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`, corresponding to build `2026081300`.

This is a clean-room behavioral inventory. Cenix does not copy Launcher3 implementation code.

| Behavior | Reference / API evidence | Cenix policy |
| --- | --- | --- |
| Stable identity | Public `UserManager.getSerialNumberForUser` | Persist profile serial only; never persist transient user IDs |
| Classification | Public API 35 `LauncherApps.getLauncherUserInfo` and `LauncherUserInfo.userType` | Personal, managed work, private, or other typed descriptors |
| Resource loading | Public `LauncherApps` and `AppWidgetManager` profile APIs | Load labels, icons, shortcuts, and widgets only while the profile is available |
| Work separation | `WorkProfileManager` uses a dedicated All Apps adapter and tab | Personal and work tabs; same package remains distinct by profile serial |
| Work pause | `WorkProfileManager.setWorkProfileEnabled` delegates quiet mode | Public `UserManager.requestQuietModeEnabled`; generic paused chrome and workspace placeholders |
| Private section | `PrivateProfileManager` places Private Space in the personal tab | Separate section with generic lock state; identities shown only while available |
| Private lock | `PrivateProfileManager` derives lock state from private-profile quiet mode | Cancel transient UI before requesting quiet mode; reload from platform callbacks |
| Folder policy | No explicit reference admission rule established | Reducer rejects mixed-profile folders |
| Reconciliation | Platform owns live application and shortcut state | Only available profile IDs are authoritative; temporary inaccessibility never means deletion |
| Permanent removal | Profile removal broadcast | Explicit typed profile-removal transaction deletes that profile's durable items |

GrapheneOS Quickstep requests non-public `ACCESS_HIDDEN_PROFILES_FULL`. Cenix never requests it. Cenix uses only public API 35 `ACCESS_HIDDEN_PROFILES`, which the API 35 AOSP HOME role granted during conformance.
