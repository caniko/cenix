# Settings, grid, and package reference

Reference scope: GrapheneOS Launcher3 branch `17`, commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`, GrapheneOS build `2026081300`. The reference checkout was clean and detached at that commit when this contract was prepared.

## Settings

| Behavior | Evidence | Cenix contract |
|---|---|---|
| Dedicated launcher settings Activity | `SRC`: `src/com/android/launcher3/settings/SettingsActivity.java`; `AndroidManifest-common.xml` | A non-exported classic-Views Activity opened by an explicit internal intent. |
| Settings survive recreation and use locale text direction | `SRC`: `SettingsActivity.onCreate`, `LauncherSettingsFragment.onViewCreated` | Durable values come from Room; Views use locale direction and stable resource IDs. |
| Default launcher selection | `API`: `RoleManager.ROLE_HOME` and `createRequestRoleIntent` | User-authorized HOME role request only. |
| Diagnostic export and reset | Cenix-local scope; no Launcher3 parity claim | Storage Access Framework export and explicit destructive confirmation. |

The pinned Launcher's own preference XML exposes notification dots and automatic icon placement, not an in-app grid picker. Launcher3 exposes grid changes through `GridCustomizationsProxy`; Cenix's finite in-app picker is therefore a product surface built from the same source grid definitions, not a claim of identical UI.

## Phone grids

`SRC`: `res/xml/device_profiles.xml` defines these phone grid options:

| Stable name | Columns x rows | Hotseat | Minimum compatible display option used by Cenix |
|---|---:|---:|---:|
| `2_by_2` | 2 x 2 | 2 | 200 x 200 dp |
| `3_by_3` | 3 x 3 | 3 | 255 x 300 dp |
| `4_by_4` | 4 x 4 | 4 | 296 x 491.33 dp |
| `4_by_5` | 4 x 5 | 4 | 367 x 838 dp |
| `5_by_5` | 5 x 5 | 5 | 406 x 694 dp |

Tablet-only grids are intentionally omitted. Cenix presents only compatible phone entries and accepts no arbitrary row or column input.

## Grid migration

| Behavior | Evidence | Cenix contract |
|---|---|---|
| Migration is decided from source and destination grid state | `SRC`: `model/GridSizeMigrationDBController.kt` | A typed `SetGrid` command carries the expected generation, target grid, page allocator, and widget minimum spans. |
| Hotseat and workspace are migrated separately | `SRC`: `GridSizeMigrationLogic.migrateHotseat`, `migrateWorkspace` | Preserve valid hotseat ranks; overflow enters deterministic workspace reflow. |
| Smaller grids add pages until all placeable items fit | `SRC`: `GridSizeMigrationLogic.placeWorkspaceItems` | Scan existing pages row-major, then allocate stable monotonic page IDs. |
| Widget placement uses minimum spans | `SRC`: `GridSizeMigrationLogic.solveGridPlacement` | Android supplies public provider minimums. Rust returns a typed blocker when a minimum cannot fit. |
| Database mutation is transactional | `SRC`: `GridSizeMigrationLogic.migrateGrid`; `SYSTEM`: Room transaction semantics | Rust plans without mutation; Room commits workspace rows and selected setting in one transaction. |

Cenix preserves item, folder, member, page, profile, and widget-binding identities. It does not copy Launcher3's temporary-table implementation because Room is the sole durable authority.

## Package lifecycle

| State/event | Evidence | Capability |
|---|---|---|
| Added, changed, removed, available, unavailable, suspended, unsuspended, loading progress, shortcuts changed | `API`: `LauncherApps.Callback`; `SRC`: `model/ModelLauncherCallbacks.kt` | `INSTALLABLE_PUBLIC` |
| Installer session enumeration and callbacks | `API`: `LauncherApps.getAllPackageInstallerSessions`, `registerPackageInstallerSessionCallback`; `SRC`: `pm/InstallSessionTracker.java` | `INSTALLABLE_PUBLIC` |
| Installed, suspended, and archived flags | `API`: `ApplicationInfo`; `SRC`: `util/ApplicationInfoWrapper.kt` | `INSTALLABLE_PUBLIC` on API 35 |
| Archived restore presentation | `SRC`: `BubbleTextView.java`, `ItemInfoWithIcon.java` | `INSTALLABLE_PUBLIC`; exact system-launcher animation omitted |
| Cross-profile package identity | `API`: `UserHandle`; `SRC`: `PackageUserKey` usage | `ROLE_HOME_GATED` for launcher profile visibility |
| Hidden/private profile discovery | P4 contract | `ROLE_HOME_GATED`; no `ACCESS_HIDDEN_PROFILES_FULL` |

Package state, labels, icons, installer-session IDs, and progress are transient Android-owned data and are never written to Room. Permanent package removal reconciles only the callback's stable profile serial. Temporary unavailability, suspension, disablement, archiving, and active sessions retain durable placement.

## Evidence limits

- `AOSP_EMU`: settings/grid/package scenarios can be automated on API 35 but do not prove GrapheneOS framework behavior.
- `GOS_DEV`: requires separately authorized `mustang` serial and exact installed GrapheneOS version evidence.
- `UNKNOWN`: archived-app setup may not be available in the AOSP emulator image; record a capability-gated result rather than fabricating coverage.
- `UNKNOWN`: TalkBack certification and measured Macrobenchmark/Perfetto jank require unavailable operator/device evidence.
