# GrapheneOS Launcher3 reference baseline

Pinned remote: `https://github.com/GrapheneOS/platform_packages_apps_Launcher3`

- Branch: `17`
- Commit: `e5fde8f4368539554b07ee136abd27bc7b4284c1`
- Observed `AndroidManifest-common.xml` / `AndroidManifest.xml` on that commit:
  - `minSdkVersion` 30
  - `targetSdkVersion` 33
  - HOME/DEFAULT intent filter on the launcher activity
  - Quickstep recents, widgets, search, overlay, and `QUERY_ALL_PACKAGES` in the common/main manifests
  - No `android.permission.INTERNET` in the sampled launcher manifests

Cenix is a new HOME app. It copies product behavior, not AOSP Java. The first slice implements only the listed-app path.

SDK mapping: GrapheneOS 17 is newer than the API 35 androidenv composition currently available here. Cenix compiles and targets API 35 until a newer platform package is available in nixpkgs. `minSdk` is 35 as specified.
