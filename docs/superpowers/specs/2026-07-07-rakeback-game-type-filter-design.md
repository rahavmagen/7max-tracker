# Rakeback Report — Game Type Filter

## Problem

The rakeback report (`GET /api/reports/admin/rakeback`, rendered on the "דוחות" / Reports page under the "ריקבק" card) sums each eligible player's rake across **all** game types for the selected date range. There's no way to compute rakeback for just one game type (e.g. only NLH), even though the underlying data (`GameSession.gameType`) already distinguishes NLH, PLO, PLO5, PLO6, SNG, MTT, AoF, SPIN_GOLD.

## Requirements

- Admin can select any combination of game types via checkboxes before running the rakeback report.
- No selection = all game types (today's current, unchanged behavior) — this must remain the default so existing usage isn't disrupted.
- Selecting one or more types restricts the rake sum (and therefore the computed rakeback amount) to only those types, for every player in the report.
- The `rakebackSince` / effective-start-date logic (a player's rakeback only counts from `MAX(dateFrom, rakebackSince)`) is unaffected by this filter — it narrows which rake counts, not the date logic.

## Data model

No schema changes. `game_sessions.game_type` already exists (`NLH`, `PLO`, `PLO5`, `PLO6`, `SNG`, `MTT`, `AoF`, `SPIN_GOLD`) and every `game_results` row links to a session via `session_id`.

## API

### `GET /api/reports/admin/rakeback` (existing endpoint, extended)
New optional query param: `gameTypes` — a comma-separated string (e.g. `NLH,PLO`), matching the existing single-`gameType`-string convention already used by `GET /api/reports/player-stats`. Omitted or empty = no filter (all games).

Backend behavior:
- Parse `gameTypes` into a `List<String>` (split on comma, trim, drop blanks).
- If the list is empty: call the existing `GameResultRepository.getRakePerPlayerBetween(from, to)` unchanged — the "all games" path is untouched code, zero regression risk.
- If the list is non-empty: call a new `GameResultRepository.getRakePerPlayerBetweenByGameTypes(from, to, gameTypes)`, a native query identical to the existing one plus `AND gs.game_type IN (:gameTypes)`.
- Both call sites in `rakebackReport` that currently call `getRakePerPlayerBetween` (the main full-period fetch, and the `effectiveFrom`-adjusted re-query for players whose `rakebackSince` is later than `dateFrom`) must use the same parsed `gameTypes` list.

## UI

On the Reports page, in the existing "ריקבק" (rakeback) card, above the date range inputs: 8 checkboxes, one per game type, labeled with the raw enum values (NLH, PLO, PLO5, PLO6, SNG, MTT, AoF, SPIN_GOLD). State is a plain array of selected type strings. On submit, if the array is non-empty, join with commas and send as `gameTypes`; if empty, omit the param entirely (preserves current "all games" request shape).

## Out of scope

- No changes to how rake itself is recorded, or to any other report (hands report, Friday rake, chip balance, etc.) — this only touches the rakeback report.
- No persistence of the selected filter (resets to "all games" on page reload, matching how the date range already behaves).
- No changes to the `rakebackPercentage`/`rakebackSince` player-level settings themselves.
