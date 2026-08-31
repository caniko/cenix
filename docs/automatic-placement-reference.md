# Automatic app placement reference

Reference: GrapheneOS Launcher3 branch `17`, commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`, GrapheneOS build `2026081300`.

| Behavior | Reference evidence | Cenix contract |
|---|---|---|
| Preference and default | `res/xml/launcher_preferences.xml` (`pref_add_icon_to_home`) | Durable Room boolean, default `true`. |
| Session source | `SessionCommitReceiver.java` | Only a successful installer session first observed before a launchable Activity exists is a new-install candidate. |
| Commit fallback | `LauncherApps.Callback.onPackageAdded` | Covers installers whose session callback arrives after commit; a seeded package/profile set rejects updates and duplicate callback paths. |
| User install policy | `SessionCommitReceiver.processIntent` | Failed sessions and packages without a launcher Activity are ignored. |
| Private profile | `SessionCommitReceiver.isEnabled` | Never auto-place private-profile applications. |
| Placement | `ItemInstallQueue`; workspace model | Select the first launchable component deterministically, reject an existing package/profile icon, scan pages row-major, and allocate one monotonic page only when full. |

Updates, replacement sessions, active-session placeholders, and package-change callbacks do not initiate placement. The Android layer classifies pre-commit sessions or a newly observed successful package add and selects a public launcher Activity; existing typed Rust commands perform page allocation and placement with generation checks, and Room remains the only durable authority.

Final AOSP emulator and release evidence is recorded in [Notification and appearance conformance](notification-appearance-conformance.md).
