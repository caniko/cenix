# spec/parity ledger

Machine-oriented copy of `docs/parity-capabilities.md`.

Evidence: `SRC` | `API` | `AOSP_EMU` | `GOS_DEV` | `SYSTEM`
Classes: `INSTALLABLE_PUBLIC` | `ROLE_HOME_GATED` | `USER_AUTHORIZED` | `SIGNATURE_OR_SYSTEM` | `INTENTIONALLY_OMITTED` | `UNKNOWN_REQUIRES_SPIKE`
`GOS_DEV` is empty until a mustang is attached.

| id | class | slice | evidence |
| --- | --- | --- | --- |
| home-register | INSTALLABLE_PUBLIC | done | API |
| home-role | ROLE_HOME_GATED | done | API |
| workspace | INSTALLABLE_PUBLIC | done | API |
| hotseat | INSTALLABLE_PUBLIC | done | API |
| all-apps | INSTALLABLE_PUBLIC | done | API |
| search | INSTALLABLE_PUBLIC | done | API |
| folders | INSTALLABLE_PUBLIC | later | SRC |
| drag-drop | INSTALLABLE_PUBLIC | done | API |
| widgets | USER_AUTHORIZED | later | API |
| widget-restore | USER_AUTHORIZED | later | SRC |
| pinned-shortcuts | USER_AUTHORIZED | later | API |
| pin-widget-confirm | USER_AUTHORIZED | later | API |
| notification-dots | USER_AUTHORIZED | later | API |
| package-events | INSTALLABLE_PUBLIC | done | API |
| session-commit | INSTALLABLE_PUBLIC | later | SRC |
| disabled-suspended | INSTALLABLE_PUBLIC | partial | API |
| work-profiles | INSTALLABLE_PUBLIC | partial | API |
| private-space | UNKNOWN_REQUIRES_SPIKE | omitted | SRC |
| app-lock | SIGNATURE_OR_SYSTEM | omitted | SYSTEM |
| wallpaper | USER_AUTHORIZED | later | API |
| grid-migration | INSTALLABLE_PUBLIC | later | SRC |
| backup | INSTALLABLE_PUBLIC | omitted | SRC |
| a11y | INSTALLABLE_PUBLIC | partial | API |
| secondary-display | INSTALLABLE_PUBLIC | omitted | API |
| recents-quickstep | SIGNATURE_OR_SYSTEM | omitted | SYSTEM |
| launcher-provider | SIGNATURE_OR_SYSTEM | omitted | SYSTEM |
| organizer | INTENTIONALLY_OMITTED | — | SRC |
| app-functions | UNKNOWN_REQUIRES_SPIKE | omitted | SRC |
| icon-packs | INSTALLABLE_PUBLIC | later | SRC |
| query-all-packages | INTENTIONALLY_OMITTED | — | — |
| internet-webview-js | INTENTIONALLY_OMITTED | — | — |
