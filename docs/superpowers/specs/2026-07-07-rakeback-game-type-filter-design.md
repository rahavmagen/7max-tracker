# Rakeback Report — Game Type Filter

## Problem

The rakeback report (`GET /api/reports/admin/rakeback`, rendered on the "דוחות" / Reports page under the "ריקבק" card) sums each eligible player's rake across **all** game types for the selected date range. There's no way to compute rakeback for just one game type (e.g. only NLH), even though the underlying data (`GameSession.gameType`) already distinguishes NLH, PLO, PLO5, PLO6, SNG, MTT, AoF, SPIN_GOLD.

## Requirements

- Admin can select any combination of game types via checkboxes before running the rakeback report.
- **Default: only NLH is checked** when the page loads (not "all games").
- **No selection (all checkboxes unchecked) = no rakeback** — every eligible player shows `totalRakePaid = 0` and `rakebackAmount = 0`. This is a deliberate change from today's behavior (which sums all game types with no filter) — the new default requires explicit, intentional selection rather than silently including every game type.
- Selecting one or more types restricts the rake sum (and therefore the computed rakeback amount) to only those types, for every player in the report.
- The `rakebackSince` / effective-start-date logic (a player's rakeback only counts from `MAX(dateFrom, rakebackSince)`) is unaffected by this filter — it narrows which rake counts, not the date logic.

## Data model

No schema changes. `game_sessions.game_type` already exists (`NLH`, `PLO`, `PLO5`, `PLO6`, `SNG`, `MTT`, `AoF`, `SPIN_GOLD`) and every `game_results` row links to a session via `session_id`.

## API

### `GET /api/reports/admin/rakeback` (existing endpoint, extended)
New optional query param: `gameTypes` — a comma-separated string (e.g. `NLH,PLO`), matching the existing single-`gameType`-string convention already used by `GET /api/reports/player-stats`.

Backend behavior:
- Parse `gameTypes` into a `List<String>` (split on comma, trim, drop blanks).
- If the list is empty: skip the rake lookup entirely — treat every player's rake-per-period map as empty, so `totalRakePaid = 0` and `rakebackAmount = 0` for everyone. No query is run.
- If the list is non-empty: call a new `GameResultRepository.getRakePerPlayerBetweenByGameTypes(from, to, gameTypes)`, a native query identical to the existing `getRakePerPlayerBetween` plus `AND gs.game_type IN (:gameTypes)`.
- Both call sites in `rakebackReport` that currently call `getRakePerPlayerBetween` (the main full-period fetch, and the `effectiveFrom`-adjusted re-query for players whose `rakebackSince` is later than `dateFrom`) must use this same logic with the same parsed `gameTypes` list.
- `getRakePerPlayerBetween` (the old, always-unfiltered query) becomes unused once this change lands — confirmed via search it has no other callers — so it is removed rather than left as dead code.

## UI

On the Reports page, in the existing "ריקבק" (rakeback) card, above the date range inputs: 8 checkboxes, one per game type, labeled with the raw enum values (NLH, PLO, PLO5, PLO6, SNG, MTT, AoF, SPIN_GOLD). State is a plain array of selected type strings, **initialized to `['NLH']`** so NLH is pre-checked on page load. On submit, join the array with commas and send as `gameTypes` (including the empty-string case when the array is empty — the backend interprets an empty/missing value as "no rakeback", not "all games").

## Out of scope

- No changes to how rake itself is recorded, or to any other report (hands report, Friday rake, chip balance, etc.) — this only touches the rakeback report.
- No persistence of the selected filter (resets to "all games" on page reload, matching how the date range already behaves).
- No changes to the `rakebackPercentage`/`rakebackSince` player-level settings themselves.
