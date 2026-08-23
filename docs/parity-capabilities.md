# Parity capabilities

## Implemented

- HOME/DEFAULT activity that can be chosen as the default home app
- All-apps list from `LauncherApps`
- Keyboard filtering and deterministic ranking
- Launch via `LauncherApps.startMainActivity`
- Work/private/hidden profile awareness: hidden profiles are omitted; visible profiles are labeled
- Process death recovery through Room metadata + crash-loop prefs
- Native filter failure or crash-loop enters Kotlin-only emergency mode
- Reset local state and retry native mode
- Prompt to open HOME settings

## Deferred

- Workspace / home-screen pages
- Folders, widgets, icon packs
- Recents / Quickstep
- Deep-shortcut surfaces
- Cuscon overlay / theme engine
- Predictive ranking
- Networked or cloud-backed features
