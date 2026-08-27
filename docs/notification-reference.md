# Notification dot reference

Reference: GrapheneOS Launcher3 branch `17`, commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`, GrapheneOS build `2026081300`.

| Behavior | Reference evidence | Cenix contract |
|---|---|---|
| User-controlled listener access | `AndroidManifest-common.xml`; `settings/NotificationDotsPreference.java` | Export exactly one service guarded by `BIND_NOTIFICATION_LISTENER_SERVICE`; Android Settings owns authorization. |
| Callback-driven state | `notification/NotificationListener.java` | Refresh only on listener connect, post, remove, or ranking callbacks. No polling or Room writes. |
| Badge eligibility | `NotificationListener.notificationIsValidForUI` | Require ranking `canShowBadge`; omit group summaries, empty title/text, and ongoing notifications on the legacy default channel. |
| Profile identity | `PackageUserKey.fromNotification` | Key transient state by package name plus stable Android user serial. |
| UI surfaces | `popup/PopupDataProvider.java`; `BubbleTextView.java`; `FolderIcon.java` | Apply dots to All Apps, workspace, hotseat, folder aggregate, open folders, and matching pinned shortcuts. |
| Pinned shortcut match | `PopupDataProvider.getDotInfoForItem` | Match public `Notification.shortcutId`. Launcher3's stored person-key fallback is not copied because public `ShortcutInfo` has no person-key getter. |
| Disconnect | `NotificationListener.onListenerDisconnected` | Clear the complete transient map immediately. |

Notification keys, titles, text, people, package identities, and counts are not logged, exported, or persisted. Cenix retains only a bounded in-memory package/profile count and bounded shortcut-ID set, and clears affected state on authorization loss, profile inaccessibility, package removal, reset, and emergency mode.

The context popup does not show notification content. The pinned source tree has dot projection but no current notification-row view implementation under `src/com/android/launcher3`; source comments and logging constants alone are not parity proof.
