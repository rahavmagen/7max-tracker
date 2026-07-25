# Club Expense "Admin Paid" — Settle Immediately, Not a Debt

## Problem

`ClubExpenseController.create()` treats every "Paid By: Admin" club expense as an unsettled debt the club owes that admin (`settled = false`, `adminUser` set, no `paidFromAdminUsername`) — requiring a separate manual "settle" action later. But the actual use case (e.g. piu piu 7 paying a Claude subscription or GREEN API bill) is that the admin is paying directly out of an admin wallet that already holds club float money — it should deduct from that wallet immediately, the same way "Paid By: Club" already deducts immediately from a bank account. There's no real "admin fronted this personally" case needed for this form.

## Fix

When `paidBy == ADMIN` in `ClubExpenseController.create()`:
- Set `paidFromAdminUsername = adminUser` (so `WalletService.computeBalance()` subtracts it from that admin's wallet balance immediately, same mechanism already used for settled club expenses).
- Also keep setting `adminUser = adminUser` (existing field, still read by `AdminExpenseController`'s settled-list display for the "who paid" label — keeping it avoids touching that consumer).
- Set `settled = true`, `settledAt = expenseDate`, `settledBy = auth.getName()`.

No frontend changes needed — the UI already only exposes "Admin paid" as a single button with no debt/settled distinction, and mirrors "Club paid directly" (which already settles immediately from a bank account). This makes the two options behave symmetrically, as the UI already implies.

## Historical data backfill

6 existing club expenses were created under the old (debt) behavior: piu piu 7's 5 (₪60 ×4 + ₪72) and RSTil's ₪4,019 VAT expense. These get updated directly:
`paid_from_admin_username = admin_user`, `settled = true`, `settled_at = expense_date`, `settled_by = admin_user`, for every row where `paid_by = 'ADMIN' AND settled = false`.

## Scope

- Only affects `ClubExpenseController.create()` — the `PATCH /{id}/settle` endpoint and `admin_expenses` table/flow (a separate concept, its own legitimate "admin fronted, owed" use case) are untouched.
- No changes to `WalletService` — the existing settled-expense subtraction logic already handles this correctly once `paidFromAdminUsername` and `settled` are set right at creation time.
