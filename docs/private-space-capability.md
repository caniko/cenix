# Private Space capability

## Public boundary

API 35 exposes `ACCESS_HIDDEN_PROFILES`, `LauncherApps.getLauncherUserInfo`, `LauncherUserInfo.userType`, `UserManager.USER_TYPE_PROFILE_PRIVATE`, profile accessibility broadcasts, and `UserManager.requestQuietModeEnabled`. An isolated API 35 AOSP image revision 2 created a real `android.os.usertype.profile.PRIVATE` profile and granted `ACCESS_HIDDEN_PROFILES` to Cenix after it became HOME.

Cenix therefore supports the public third-party HOME boundary:

- discover and classify the private profile by stable serial and public user type
- show a separate Private Space section only while that profile is known
- resolve and launch private applications only while the profile is available
- request platform-owned lock/unlock through quiet mode
- clear search results, context UI, folders, drags, labels, icons, shortcuts, and widget resources on lock/inaccessibility
- reject private workspace application/shortcut placement and incoming private shortcut/widget pins
- keep logs and diagnostic exports free of private package, class, label, user, serial, shortcut, widget, and provider identity

## Deliberate exclusions

- No `ACCESS_HIDDEN_PROFILES_FULL`, hidden API, reflection, signature permission, or production shell command.
- No copied Launcher3 Private Space settings, install-app, animation, or system-app behavior.
- No private widgets or durable private workspace items. API 35 marks private-profile items restricted on the home screen, and Cenix chooses the smaller non-leaking policy.
- No claim of GrapheneOS `mustang` behavior until a physical device run exists.

The `profiles` emulator suite records profile creation output, HOME-role permission state, user state, managed-profile DPC setup, and lock-time UI/log/diagnostic leakage checks. The DPC exists only in the test fixture to allow a managed-profile widget provider, as required by Android policy.
