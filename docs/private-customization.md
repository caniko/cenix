# Private Space customization

The installable APK uses **transient private presentation only**. It does not
implement credential-bound persistent private categories or individual icon overrides.
No root, hidden cross-user APIs or system installation is required.

- Private apps stay in the isolated Private Space section, outside category counts.
- The shared installed icon pack can supply resources while private apps are visible.
- Per-app customization actions are hidden for private/unknown profiles. Store setters
  reject them, and reads ignore any legacy private overrides even before cleanup.
- Catalog refresh retains durable assignments only for identified personal/work profiles,
  purging legacy private and removed-profile rows, including when Private Space is unlocked.
- Backup export allows personal and explicitly selected work profiles only.

Tests: `PrivateCustomizationTest`, `DrawerBackupTest`, and the existing profile policy
tests. Physical GrapheneOS lock/unlock, memory inspection and TalkBack qualification
remain unperformed; these tests do not claim device-level credential isolation.

Persistent private customization would need a separately verified storage design that
follows the private lock while the owner remains unlocked. Owner-profile Keystore
encryption is not equivalent. Such a design is not part of this APK release scope.
