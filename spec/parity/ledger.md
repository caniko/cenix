# spec/parity ledger

Machine-oriented copy of `docs/parity-capabilities.md`.

Evidence: `SRC` | `API` | `AOSP_EMU` | `GOS_DEV` | `SYSTEM` | `UNKNOWN`
Classes: `INSTALLABLE_PUBLIC` | `ROLE_HOME_GATED` | `USER_AUTHORIZED` | `TRANSPORT_CONTROLLED` | `SIGNATURE_OR_SYSTEM` | `INTENTIONALLY_OMITTED` | `UNKNOWN_REQUIRES_SPIKE`
`GOS_DEV` is empty until a mustang is attached.

| id | class | slice | evidence |
| --- | --- | --- | --- |
| home-register | INSTALLABLE_PUBLIC | done | API |
| home-role | ROLE_HOME_GATED | done | API |
| workspace | INSTALLABLE_PUBLIC | done-p1 | API,AOSP_EMU |
| hotseat | INSTALLABLE_PUBLIC | done-p1 | API,AOSP_EMU |
| all-apps | INSTALLABLE_PUBLIC | stacked-p1; shell-p1.5 | API |
| search | INSTALLABLE_PUBLIC | done | API |
| folders | INSTALLABLE_PUBLIC | done-p2a | API,AOSP_EMU |
| drag-drop | INSTALLABLE_PUBLIC | internal-done-p1; folders-done-p2a | API,AOSP_EMU |
| widgets | USER_AUTHORIZED | p3-contract | SRC,API |
| widget-restore | USER_AUTHORIZED | p3-contract | SRC,API |
| pinned-shortcuts | USER_AUTHORIZED | done-p2b | API,AOSP_EMU |
| pin-widget-confirm | USER_AUTHORIZED | p3-contract | SRC,API |
| notification-dots | USER_AUTHORIZED | implemented-p5b | SRC,API |
| package-events | INSTALLABLE_PUBLIC | done | API,AOSP_EMU |
| auto-placement | INSTALLABLE_PUBLIC | implemented-p5b | SRC,API |
| disabled-suspended | INSTALLABLE_PUBLIC | partial | API |
| work-profiles | INSTALLABLE_PUBLIC | done-p4 | SRC,API,AOSP_EMU |
| private-space | ROLE_HOME_GATED | public-boundary-p4 | SRC,API,AOSP_EMU |
| app-lock | SIGNATURE_OR_SYSTEM | omitted | SYSTEM |
| wallpaper | USER_AUTHORIZED | implemented-p5b | SRC,API |
| themed-icons | INSTALLABLE_PUBLIC | implemented-p5b | SRC,API |
| grid-migration | INSTALLABLE_PUBLIC | done-p5a | SRC,API,AOSP_EMU |
| backup-auto | TRANSPORT_CONTROLLED | contract-p5c | SRC,API |
| backup-layout | USER_AUTHORIZED | contract-p5c | SRC,API |
| backup-transport | TRANSPORT_CONTROLLED | execution-unproven | UNKNOWN |
| a11y | INSTALLABLE_PUBLIC | keyboard-and-semantics-p2b; TalkBack-unverified | API,AOSP_EMU |
| secondary-display | INSTALLABLE_PUBLIC | omitted | API |
| recents-quickstep | SIGNATURE_OR_SYSTEM | omitted | SYSTEM |
| launcher-provider | SIGNATURE_OR_SYSTEM | omitted | SYSTEM |
| organizer | INTENTIONALLY_OMITTED | — | SRC |
| app-functions | UNKNOWN_REQUIRES_SPIKE | omitted | SRC |
| icon-packs | INTENTIONALLY_OMITTED | — | SRC |
| query-all-packages | INTENTIONALLY_OMITTED | — | — |
| internet-webview-js | INTENTIONALLY_OMITTED | — | — |
