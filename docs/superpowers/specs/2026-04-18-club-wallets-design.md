# Club Wallets — Design Spec
**Date:** 2026-04-18
**Status:** Awaiting user approval

---

## Overview

Today the club's money is tracked as a single "Bank Balance" number. In reality, cash is physically held by individual admins and in bank accounts. This feature introduces **Club Wallets** — a per-holder view of where club money actually sits, with full transaction history. The sum of all wallets equals what is today called Bank Balance.

---

## Core Concepts

| Concept | Meaning |
|---|---|
| **Admin Wallet** | Club cash physically held by an admin. Computed from their transaction history — not manually set. |
| **Bank Account** | Existing bank account entities. Already tracked, included in total. |
| **Club Total** | Sum of all admin wallets + all bank accounts. Replaces "Bank Balance" in Total Profit. |

Admin Expenses (admin pays out of personal pocket) are **separate** from Admin Wallets. An unpaid admin expense is a debt the club owes the admin, not cash they're holding.

---

## Database Changes

### 1. `player_transfers` — two new nullable columns
```sql
ALTER TABLE player_transfers ADD COLUMN from_admin_username VARCHAR(255);
ALTER TABLE player_transfers ADD COLUMN to_admin_username VARCHAR(255);
```
- `from_admin_username` — set when CLUB is the sender (which admin gave the cash)
- `to_admin_username` — set when CLUB is the receiver (which admin received the cash)
- Existing rows: both columns NULL (shown as "Unassigned" in history)

### 2. `admin_expenses` — payment source columns + remove VAT tracking
```sql
ALTER TABLE admin_expenses ADD COLUMN paid_from_admin_username VARCHAR(255);
ALTER TABLE admin_expenses ADD COLUMN paid_from_bank_account_id BIGINT;
-- VAT type column becomes unused (no removal needed, just ignored in UI)
```
- When an admin expense is paid, record which admin wallet or bank account the payment came from
- This creates a debit on that wallet

### 3. `club_expenses` — same payment source columns
```sql
ALTER TABLE club_expenses ADD COLUMN paid_from_admin_username VARCHAR(255);
ALTER TABLE club_expenses ADD COLUMN paid_from_bank_account_id BIGINT;
```

---

## Admin Wallet Balance Computation

For each admin, balance is computed live from:

```
+ player_transfers where to_admin_username = admin       (received cash for club)
- player_transfers where from_admin_username = admin     (gave cash out for club)
- admin_expenses where paid_from_admin_username = admin  (paid an admin expense)
- club_expenses where paid_from_admin_username = admin   (paid a club expense)
```

No stored balance column — always derived.

---

## Transfer Form Changes (Transfers Page)

When **CLUB** is selected as From or To, a secondary dropdown appears beneath it:

> **Handled by admin:** `[dropdown of all admin-role users]`

- If CLUB is From → sets `from_admin_username`
- If CLUB is To → sets `to_admin_username`
- Field is optional (old transfers have no admin set)

**Admin-to-admin internal transfer** = CLUB (Admin A) → CLUB (Admin B):
- `from_admin_username = Admin A`, `to_admin_username = Admin B`
- Reduces Admin A's wallet, increases Admin B's wallet
- No new transfer type needed

---

## Admin Expenses Page Changes

### Simplified expense grouping
- Remove VAT / No VAT split entirely
- One unified list per admin: **Unsettled** expenses
- One unified **Paid** section (all paid expenses together)

### "Pay" button
Replaces current "No VAT" / "VAT" buttons with a single **"Pay"** button.
Clicking opens a small inline form:

> **Paid from:** `[Admin wallet dropdown | Bank account dropdown]`
> **[Confirm Pay]**

On confirm:
- Expense marked as paid (settledAt = today)
- Sets `paid_from_admin_username` or `paid_from_bank_account_id` on the expense
- This deducts from the selected admin's wallet balance automatically

---

## New "Club Wallets" Page

### Route
`/club-wallets` — new nav item (admin only)

### Layout

**Summary card at top:**
| Holder | Balance |
|---|---|
| Admin A | ₪3,500 |
| Admin B | ₪1,200 |
| Bank Account 1 | ₪10,000 |
| **Club Total** | **₪14,700** |

**History section below:**
Full chronological log of all wallet events across all admins and bank accounts, with columns:
- Date | Type | From | To | Amount | Notes

Filterable by date range and by holder (admin or bank account).

**Unassigned section:**
Transfers where CLUB was involved but no admin was specified (legacy data). Shown separately, not included in any admin's balance.

---

## Total Profit Page Changes

- "Bank Balance" line → value becomes a **clickable link** → navigates to `/club-wallets`
- The value shown = Club Total (sum of all admin wallets + bank accounts)
- No change to how the value is computed (still uses `bankDeposits` from `ProfitSummary` / `ImportSummary`)

> **Note:** The Total Profit "Bank Balance" value currently comes from `bankDeposits` (sum of player transfers to the club from XLS/manual entries). The Club Wallets total is computed live from `player_transfers` with admin attribution. These two should align for new transfers once admin fields are populated, but legacy unattributed transfers will cause a gap — out of scope for this spec to reconcile.

---

## What Is Out of Scope

- Manual balance adjustments on admin wallets (explicitly excluded)
- Backfilling existing transfers with admin attribution
- Removing the existing Bank Balance page (stays as-is)

---

## Bank Balance Manual Correction

The current `ImportSummary.bankDeposits` value may be stale or incorrect. Admins need a way to set it to the correct figure.

**Approach:** A simple "Set bank balance" input on the Club Wallets page. Submitting it calls a `PATCH /api/import-summary/bank-balance` endpoint that overwrites `bankDeposits` and records an audit log entry (a `PlayerTransfer` row with `method = ADJUSTMENT` and `notes = "Manual bank balance correction"`).

This does NOT affect admin wallet balances — it only corrects the raw XLS-base number that feeds into the bank account display.

---

## Ticket Assets

### Overview

Admins buy live tournament tickets in advance at a discounted cost (e.g., 950 for a face-value 1,000 ticket). These are club assets — buying tickets does not affect profit because cash is exchanged for inventory of equal face value.

### Lifecycle

1. **Buy:** Admin pays out of pocket → inventory row created, admin expense created (admin is owed cost × quantity)
2. **Satellite event:** Runs through normal game session (5 × 200 = 1,000 collected, winner gets +1,000 chips) — no special tracking needed here
3. **Grant:** Winner is given the physical ticket → inventory decremented, chip deduction transaction created (−face_value chips from player)
4. **Reimburse:** Admin is paid back via the normal Admin Expenses "Pay" flow (no automatic trigger)

### Database

```sql
CREATE TABLE ticket_assets (
    id BIGSERIAL PRIMARY KEY,
    cost_per_ticket NUMERIC(12,2) NOT NULL,
    face_value_per_ticket NUMERIC(12,2) NOT NULL,
    quantity_total INTEGER NOT NULL,
    quantity_remaining INTEGER NOT NULL,
    buyer_admin_username VARCHAR(255) NOT NULL,
    purchase_date DATE NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW()
);
```

### Balance sheet impact

- `GET /api/ticket-assets/summary` returns `{ totalFaceValue: SUM(quantity_remaining × face_value_per_ticket) }`
- Total Profit page shows a **"Ticket Assets"** line using this value
- When all tickets are granted, the line returns to zero

### Admin expense auto-creation

When tickets are bought, the backend auto-creates an `AdminExpense`:
- `adminUsername = buyer_admin_username`
- `amount = cost_per_ticket × quantity_total`
- `notes = "Ticket purchase: {qty}x ₪{face_value} tickets"`
- `expenseDate = purchase_date`

This makes the debt appear in the buyer's wallet automatically.

### Grant flow

On the Ticket Assets page, each ticket type with `quantity_remaining > 0` shows a **Grant** button. Clicking opens an inline form:

> **Player:** `[player search/dropdown]`
> **[Confirm Grant]`

On confirm:
- `quantity_remaining` decremented by 1
- A `Transaction` created: player = selected player, amount = −face_value, type = TICKET_GRANT, notes = "Ticket grant"

### UI — Ticket Assets page

New route `/ticket-assets`, new nav item.

**Inventory table:**
| Buyer | Cost | Face Value | Remaining | Total | Purchase Date | Actions |
|---|---|---|---|---|---|---|
| admin1 | ₪950 | ₪1,000 | 8 | 10 | 2026-04-15 | [Grant] |

**Buy tickets form (at top):**
- Admin picker, cost per ticket, face value per ticket, quantity, date, notes

**Total assets line:** Sum of all remaining × face value

---

## API Endpoints

### New
- `GET /api/wallets/summary` — returns all admin balances + bank account balances + total
- `GET /api/wallets/history` — returns full wallet event history (filterable)
- `PATCH /api/import-summary/bank-balance` — set `bankDeposits` to a specific value
- `GET /api/ticket-assets` — list all ticket asset rows
- `GET /api/ticket-assets/summary` — `{ totalFaceValue }` for profit page
- `POST /api/ticket-assets` — buy tickets (creates inventory row + admin expense)
- `POST /api/ticket-assets/{id}/grant` — grant one ticket to a player (decrements inventory, creates transaction)

### Modified
- `POST /api/transfers` — accepts optional `fromAdminUsername`, `toAdminUsername`
- `POST /api/admin-expenses/{id}/pay` — accepts `paidFromAdminUsername` or `paidFromBankAccountId`
- `POST /api/club-expenses/{id}/pay` — same
- `GET /api/admin-expenses` — response simplified (VAT grouping removed from response shape)

---

## Frontend Files Affected

| File | Change |
|---|---|
| `src/pages/Transfers.jsx` | Add "Handled by admin" dropdown when CLUB is selected |
| `src/pages/AdminExpenses.jsx` | Simplify to one group, replace VAT buttons with "Pay" + source picker |
| `src/pages/TotalProfit.jsx` | Make Bank Balance line clickable, link to `/club-wallets`; add Ticket Assets line |
| `src/pages/ClubWallets.jsx` | **New page** — admin balances, history, bank balance correction input |
| `src/pages/TicketAssets.jsx` | **New page** — ticket inventory, buy form, grant action |
| `src/App.jsx` | Add routes + nav items for Club Wallets and Ticket Assets |
| `src/api.js` | Add new API calls |

---

## Backend Files Affected

| File | Change |
|---|---|
| `PlayerTransfer.java` | Add `fromAdminUsername`, `toAdminUsername` fields |
| `AdminExpense.java` | Add `paidFromAdminUsername`, `paidFromBankAccountId` fields |
| `ClubExpense.java` | Add `paidFromAdminUsername`, `paidFromBankAccountId` fields |
| `PlayerTransferController.java` | Accept new admin fields on create |
| `AdminExpenseController.java` | New pay endpoint with source picker, simplify response |
| `ClubExpenseController.java` | New pay endpoint with source picker |
| `WalletController.java` | **New** — summary + history endpoints |
| `WalletService.java` | **New** — balance computation logic |
| `ImportSummaryController.java` | **New** — bank balance correction endpoint |
| `TicketAsset.java` | **New entity** |
| `TicketAssetRepository.java` | **New** |
| `TicketAssetController.java` | **New** — buy, list, grant, summary endpoints |
| `SchemaMigration.java` | Add new columns + ticket_assets table |
