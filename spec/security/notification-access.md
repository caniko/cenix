# Notification access security boundary

## Trust boundary

- Android Settings is the sole authority that grants or revokes notification-listener access.
- The manifest declares a service binding permission, not an ordinary uses-permission.
- Cenix never requests notification-policy access and has no silent authorization path.

## Data minimization

- Read only callback-delivered active notification metadata needed for badge eligibility, package/user aggregation, and public shortcut-ID matching.
- Retain at most 4096 active inputs per refresh, counts capped at 999, and 64 shortcut IDs per package/profile aggregate.
- Retain no notification key, title, text, person, intent, remote view, URI, or payload.
- Write no notification-derived value to Room, files, logs, diagnostics, UniFFI, network, clipboard, or backup.

## Clearing

The complete map clears on disconnect, local disable, reset, and emergency mode. Package removal clears its package/profile entry. Quiet, locked, unavailable, and removed profiles clear their entries and are excluded during every listener refresh.

## Failure policy

Security exceptions and inaccessible users produce no dots. Missing authorization leaves the preference visible with an access-required state and routes the user to Android's listener-detail settings.
