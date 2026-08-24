# ADR 0011: Persistent hotseat row

## Status

Accepted.

## Context

Workspace pages hide when you swipe. A dock should stay put. GrapheneOS removed the hotseat QSB; Cenix never had one.

## Decision

Reuse `workspace_items` with `screen = -1`. One row, `PhoneGrid.cols` slots. Long-press All Apps pins to the workspace; a second long-press on the same app moves it to the hotseat. Long-press a cell unpins. No drag, no QSB.

## Consequences

An app lives on one surface only (unique package/class/profile). Extra pages and folders stay out.
