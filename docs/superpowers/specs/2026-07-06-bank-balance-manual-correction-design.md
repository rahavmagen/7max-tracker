# Manual Bank Balance Correction

## Problem

The tracked club "Bank" total (`ImportSummary.bankDeposits`) is normally kept in sync by XLS uploads and by Player Transfers that link a real `BankAccount`. There's no way to correct it directly when it drifts from the real bank statement (e.g. bank fees, an unrecorded deposit, an XLS parsing gap) — the only prior option was a one-off manual SQL update.

## Requirements

- An admin/manager can set the Bank balance to a new absolute value, as often as needed (not a one-time setup step).
- Each correction is recorded as an immutable, auditable entry — never silently overwritten.
- Corrections are visible in the existing Bank history/drill-down (the same list shown when filtering Club Wallets by "Bank"), clearly distinguished from real transfers.
- Only Admin/Manager roles can perform this (same access gate as the rest of the Wallets/Transfers pages).

## Data model — reusing existing infrastructure

While investigating, we found this feature already has a half-built backend: `ImportSummaryController.setBankBalance` (`PATCH /api/import-summary/bank-balance`) and a matching frontend helper (`api.js: setBankBalance`) already exist, but are called from nowhere in the UI. It logs each correction as a `PlayerTransfer` row with `method=ADJUSTMENT` — confirmed via production DB query that this has never actually been used (zero rows with that method exist). Rather than add a new `BankAdjustment` table, this plan finishes wiring up that existing mechanism:

- No new entity/table. Each correction is a `PlayerTransfer` row (`fromPlayer`/`toPlayer`/`fromBankAccount`/`toBankAccount` all null, `method = ADJUSTMENT`, `confirmed = true`) — immutable, never edited or deleted, same as other transfer rows.
- The endpoint is changed to store the row's `amount` as the **signed delta** (`newBalance - previousBalance`) instead of the absolute new balance, since that's what the history reconciliation math needs (safe change — verified zero existing rows depend on the old semantics).
- `notes` is auto-composed as `"Balance corrected: {previous} -> {new}"`, optionally appended with the admin's own note.

## API

### `PATCH /api/import-summary/bank-balance` (existing endpoint, hardened)
Body: `{ "bankBalance": number, "notes"?: string }`

- Rejects if `bankBalance` is missing, non-numeric, or negative (400).
- Rejects for player-role callers (403), matching the `isPlayer(auth)` gate used elsewhere (e.g. `WalletController`).
- Reads current `ImportSummary.bankDeposits` (creating the row with id=1 if absent, defaulting previous to 0).
- Sets `ImportSummary.bankDeposits = bankBalance`, `lastUpdated = now`.
- Saves a `PlayerTransfer` audit row: `amount = bankBalance - previousBalance`, `method = ADJUSTMENT`, composed `notes`, `createdByUsername`, `transferDate = today`, `confirmed = true`.
- Returns `{ previousBalance, newBalance, delta }`.

### `GET /api/transfers/bank-history` (existing endpoint, extended)
Currently returns rows of type `XLS` (a single derived "base" plug row) and `TRANSFER` (from `findBankRelatedTransfers()`), reconciled so that:

```
currentBank = xlsBase + sum(transfer deltas)
```

`findBankRelatedTransfers()` is extended to also include rows where `method = ADJUSTMENT` (previously excluded entirely, since they have no player/bank-account linkage). These rows are labeled `type: "ADJUSTMENT"` instead of `"TRANSFER"`, with `fromName`/`toName` left blank (there's no real counterparty), and their `delta` is the row's `amount` directly (already a signed delta, unlike real transfers which need direction inferred from to/from fields). Reconciliation still holds automatically since adjustment deltas are summed into the same running total as transfer deltas:

```
xlsBase = currentBank - sum(transfer deltas + adjustment deltas)
```

## UI

On the Club Wallets page, next to the existing "🏦 Bank" wallet card: an "Update Balance" button opens a small inline form with:
- **New Balance** (₪, required, non-negative)
- **Note** (optional, free text)

On submit, calls the new endpoint, shows a success message with the old → new change, and reloads the page data (same `load()` pattern already used for other actions on this page).

In the Bank history/drill-down list, `ADJUSTMENT` rows render with a distinct label (e.g. "🔧 Correction") instead of the transfer icon, so they're visually distinguishable from real transfers at a glance.

## Out of scope

- No approval workflow — a correction takes effect immediately (mirrors direct SQL admin behavior it replaces).
- No editing or deleting past corrections through the UI.
- No changes to how XLS uploads or Player Transfers already update the Bank total — this only adds a third, manual input source.
