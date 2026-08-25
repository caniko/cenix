# Folder parity contract

Evidence labels are `SRC`, `API`, `AOSP_EMU`, `GOS_DEV`, and `UNKNOWN`.

## Domain contract

- A folder is a real typed launcher item. Its item ID is also its folder ID.
- Applications and folders are the only P2A payloads. Nested folders are invalid.
- Application item IDs survive moves into, between, and out of folders.
- A component may occur once across grid placements and folder memberships.
- Folder member ranks are contiguous and deterministic.
- A folder may contain applications from exactly one profile. Cross-profile parity is `UNKNOWN`; Cenix rejects mixed profiles.
- Empty titles are valid. Titles are at most 80 Unicode code points and contain no control characters.
- Folders with fewer than two members dissolve transactionally. A sole member takes the folder placement.

## Interaction contract

- The central half of an occupied application cell activates folder creation and a visible preview.
- Leaving that region cancels folder intent. Dropping without folder intent uses occupied-cell reorder.
- Creation ranks the destination first and dragged source second.
- A folder icon previews at most four leading members and exposes title, count, and placement semantics.
- The popup is transient Kotlin state. Back closes the title IME first, then the popup. HOME, outside tap, and the close action close it.
- Closed-folder drops append. Open-folder drops and reorders use explicit ranks.
- Cancelled member drags do not mutate Room.
- Folder icons use the existing workspace/hotseat/page movement path.
- Package reconciliation removes missing members and applies dissolution in the same generation transaction.

## Scope

P2A excludes shortcuts, widgets, dots, work-profile tabs, Private Space chrome, icon packs, backup, secondary displays, and Quickstep.
