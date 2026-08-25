# Widget reference

Reference: GrapheneOS `platform_packages_apps_Launcher3`, branch `17`, commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`, plus public Android app-widget APIs.

| Behavior | Cenix boundary | Reference pattern |
| --- | --- | --- |
| Discovery | Kotlin reads home-screen providers from `AppWidgetManager`; the picker is transient launcher UI | `WidgetsModel`, `WidgetPickerActivity` |
| Allocation and bind | Journal allocation before calling `AppWidgetHost.allocateAppWidgetId`; use `bindAppWidgetIdIfAllowed` or `ACTION_APPWIDGET_BIND` | `WidgetHostViewLoader`, `LauncherWidgetHolder.startBindFlow` |
| Configuration | Required configuration completes before durable placement; optional/reconfigurable providers may skip first-run configuration | `WidgetAddFlowHandler.needsConfigure` |
| Rendering and updates | Kotlin creates and owns a real `AppWidgetHostView`; host listening follows visible activity lifecycle | `LauncherWidgetHolder`, `LauncherAppWidgetHost` |
| Cancellation | Delete every allocated but uncommitted platform ID and clear its journal row | `WidgetHostViewLoader` |
| Durable identity | Rust stores provider package/class/profile and spans; Room alone maps launcher `item_id` to nullable unique Android `appWidgetId` | Cenix architecture boundary |
| Resize | Rust validates occupied spans; Kotlin additionally clamps direction/min/max provider constraints and updates widget size options | `LauncherAppWidgetProviderInfo.initSpans`, `WidgetSizeHandler` |
| Pin request | Validate `PinItemRequest`, show explicit confirmation, then accept with the allocated ID only after placement can commit | `PinWidgetFlowHandler` |
| Restore | Validate host ID and equal old/new arrays, then journal and transactionally remap Room IDs | `AppWidgetsRestoredReceiver` |
| Missing provider or binding | Keep a removable placeholder without fabricating `RemoteViews` | `PendingAppWidgetHostView` |

The reference is behavioral only. Cenix does not copy Launcher3 internals, database code, custom widget support, Quickstep integration, Compose picker code, or private/system APIs.
