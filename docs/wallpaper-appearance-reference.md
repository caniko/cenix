# Wallpaper and appearance reference

Reference: GrapheneOS Launcher3 branch `17`, commit `e5fde8f4368539554b07ee136abd27bc7b4284c1`, GrapheneOS build `2026081300`.

| Behavior | Reference evidence | Cenix contract |
|---|---|---|
| Empty-workspace order | `popup/WorkspaceLongPressOptions.kt` | Wallpaper, widgets, All Apps, then launcher settings. |
| Picker ownership | `WorkspaceLongPressOptions.startWallpaperPicker` | Launch public `Intent.ACTION_SET_WALLPAPER` only when `WallpaperManager` reports support and policy allowance. |
| Color observation | `util/WallpaperColorHints.kt` | One lifecycle-bound `OnColorsChangedListener` per visible Activity, filtered to `FLAG_SYSTEM`. |
| Light/dark treatment | `util/Themes.java`; `WallpaperThemeManager.kt` | Use public wallpaper hints to select DayNight mode and system-bar icon contrast. |
| Dynamic palette | `res/values-v31/colors.xml` | Consume Android system dynamic-color resources; do not calculate or persist a custom palette. |
| Themed icons | `graphics/theme/ThemePreference.kt`; `graphics/IconLoader.kt` | User preference defaults off. Use public `AdaptiveIconDrawable.monochrome`, preserve the platform mask and user badge, and fall back to the original icon. |

Wallpaper URIs, bytes, colors, and palette values never enter Room or diagnostics. The shared icon cache is bounded to 256 entries and invalidated by package/profile, density, preference, and wallpaper appearance generation. Icon loading and theming occur on Cenix's existing background executor.

The pinned launcher settings XML does not contain a wallpaper preference, so Cenix exposes wallpaper in the reference-ordered empty-workspace menu and as an explicit appearance action rather than claiming settings-screen parity.
