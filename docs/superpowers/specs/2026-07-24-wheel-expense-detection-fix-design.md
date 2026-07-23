# Wheel Expense Detection Fix

## Problem

The club runs a daily lottery ("wheel") for players who register early to a tournament: one winner per satellite has their entry cost refunded as chips. The XLS import has no explicit "this was a wheel win" flag from ClubGG — the system infers it by matching club→player "Send Chips" amounts against tournament buy-ins that day.

The current matching (`getNightlyMttCost` in `ReportService.java`) is too broad: it checks whether a specific player's *own* buy-in that day (which can include re-entries — e.g. a double buy-in totaling ₪106 or ₪500) equals the transferred amount, with no upper bound. This produces false positives whenever a club→player payment coincidentally equals a main-event ticket or a re-entry total, rather than the actual single-ticket satellite cost (usually ₪40-80).

Verified against a week of production data: 7 of 16 "wheel expense" records were misclassified this way — all matched either a re-entry total (₪106 = 53×2) or a main-event buy-in/re-entry (₪250, ₪500), never an actual satellite base price.

## Corrected rule

A club→player payment is a wheel expense only if **all** of:
1. The player has a `GameResult` in some MTT session that day (they played a tournament).
2. The payment amount equals **that session's base entry cost** — the minimum `buyIn` across *all* players' results in that session (representing one ticket, not a re-entry multiple), not the specific player's own (possibly re-entry-inflated) buy-in.
3. That base cost is under ₪100.

This naturally excludes the main event (base cost always ≥ ₪200 in observed data) and excludes re-entry totals (since a re-entry amount is never itself a session's *minimum* buy-in) — without needing to identify "satellite" sessions by name.

## Implementation

`getNightlyMttCost(date, playerId, amount, consumedGames)` in `ReportService.java`:
- Currently: for each MTT session that day, filters that session's results to the given player, and checks if *their own* `buyIn` equals `amount`.
- Change to: for each MTT session that day, first confirm the player has a result in that session (participated), then compute the session's base cost as `MIN(buyIn)` across *all* results in that session, and check if `amount` equals that base cost.
- Add a ceiling: only treat a match as valid if the matched cost is `< 100` (a named constant, `WHEEL_MAX_AMOUNT`).
- Keep existing behavior otherwise: full-day window, previous-day fallback if no match found on the transaction date, and the `consumedGames` per-player-per-session tracking to avoid double-matching one session within an import run.

The sibling function `getNightlyMainEntryCost` (used for the opposite-signed "Send Chips" case) has the same missing-ceiling issue and gets the same `< 100` guard added, for consistency, without needing the same base-cost rework since it already uses session-level `MIN(buyIn)`.

## Scope

- Only affects future XLS uploads — no changes to the 7 already-misclassified records from this week (left for manual review, per explicit decision).
- No changes to session-type identification, satellite naming conventions, or any other part of the XLS import.
- No changes to the historical, once-off "XLS:WHEEL" bulk AdminExpense record (from March 2026, unrelated to this per-trade detection path).

## Out of scope

- No UI changes — this is a backend detection-logic fix only.
- No configurability for the ₪100 ceiling (hardcoded constant, matching how MTT costs are already derived from data rather than config elsewhere in this codebase).
