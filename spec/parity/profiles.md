# Profile parity contract

## Authority

- Android owns live users, profile types, quiet/locked state, applications, shortcuts, widgets, and authentication.
- Room remains the sole durable launcher authority.
- Stable profile serials cross typed UniFFI; `UserHandle`, user IDs, labels, icons, and platform objects never do.
- Rust projects typed `(kind, access, surface)` descriptors and applies generation-checked reconciliation/removal commands without retaining state.

## Work profile

- Personal and work applications are separate by profile serial even when package/class match.
- Work applications, shortcuts, and DPC-allowed widgets use the matching `UserHandle`.
- Quiet mode hides live resources but preserves generic workspace placeholders and durable rows.
- Unquiet restores resources without recreating durable items.
- Per-profile package removal does not affect the same package in another profile.
- Permanent profile removal transactionally removes that profile's applications, shortcuts, widgets, and folder members.

## Private profile

- Private applications appear only in a separate personal-tab section while available.
- Lock/inaccessibility removes identities from all launcher surfaces before the platform request is issued and again on callbacks.
- Private applications, shortcuts, and widgets cannot be persisted to the workspace or accepted through incoming pin flows.
- System authentication and profile lifecycle remain Android-owned.

Evidence labels are `API`, `AOSP_EMU`, and `GOS_DEV`. No `GOS_DEV` evidence exists.
