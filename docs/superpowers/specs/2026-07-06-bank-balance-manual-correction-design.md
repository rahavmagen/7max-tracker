# Manual Bank Balance Correction

## Problem

The tracked club "Bank" total (`ImportSummary.bankDeposits`) is normally kept in sync by XLS uploads and by Player Transfers that link a real `BankAccount`. There's no way to correct it directly when it drifts from the real bank statement (e.g. bank fees, an unrecorded deposit, an XLS parsing gap) — the only prior option was a one-off manual SQL update.

## Requirements

- An admin/manager can set the Bank balance to a new absolute value, as often as needed (not a one-time setup step).
- Each correction is recorded as an immutable, auditable entry — never silently overwritten.
- Corrections are visible in the existing Bank history/drill-down (the same list shown when filtering Club Wallets by "Bank"), clearly distinguished from real transfers.
- Only Admin/Manager roles can perform this (same access gate as the rest of the Wallets/Transfers pages).

## Data model

New entity `BankAdjustment` (table `bank_adjustments`):

| column | type | notes |
|---|---|---|
| id | bigint, identity | |
| previous_balance | numeric | snapshot of `ImportSummary.bankDeposits` before this correction |
| new_balance | numeric | the value the admin entered |
| notes | varchar, nullable | optional reason |
| created_by_username | varchar | |
| created_at | timestamp | |

Rows are never updated or deleted — each correction is a permanent record.

## API

### `POST /api/wallets/bank-balance`
Body: `{ "newBalance": number, "notes"?: string }`

- Rejects if `newBalance` is missing, non-numeric, or negative (400).
- Rejects for non-admin/manager callers (403), matching `WalletController.isPlayer()` gate used elsewhere.
- Reads current `ImportSummary.bankDeposits` (or `ImportSummary.id=1`, creating if absent, defaulting previous to 0).
- Saves a `BankAdjustment` row (`previousBalance` = current value, `newBalance` = input, `notes`, `createdByUsername` = authenticated user, `createdAt` = now).
- Sets `ImportSummary.bankDeposits = newBalance`, `lastUpdated = now`.
- Returns the updated summary (previous/new/delta).

### `GET /api/player-transfers/bank-history` (existing endpoint, extended)
Currently returns rows of type `XLS` (a single derived "base" plug row) and `TRANSFER` (from `findBankRelatedTransfers()`), reconciled so that:

```
currentBank = xlsBase + sum(transfer deltas)
```

This endpoint is extended to also merge in `BankAdjustment` rows as `type: "ADJUSTMENT"` (delta = `new_balance - previous_balance`, plus `notes`, `date` = `created_at`, `createdBy`), and the reconciliation formula becomes:

```
xlsBase = currentBank - sum(transfer deltas) - sum(adjustment deltas)
```

so the derived "XLS base" line still nets out correctly with corrections included. Rows are merged and sorted newest-first alongside existing rows, as today.

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
