# Admin Expense — Settle Immediately, Not a Debt

## Problem

Same underlying issue as the club_expenses fix, in a sibling table: `AdminExpenseController.create()` (the manual "add admin expense" form, `POST /admin-expenses`) always creates the record as an unsettled debt (`settled` unset, no `paidFromAdminUsername`). But the actual scenario is the same as before — the admin is paying out of a wallet that already holds club float money, not fronting personal funds. There should be one unified balance per admin: if an expense pushes that balance negative, that negative number *is* how "the club owes this admin" is expressed. There's no need for a separate "debt" activity type.

## Fix

In `AdminExpenseController.create()`: set `paidFromAdminUsername = adminUsername`, `settled = true`, `settledAt = expenseDate`, `settledBy = auth.getName()`.

## Explicitly out of scope

- The `ImportService` "הוצאות" sheet XLS import path (a separate `AdminExpense` creation point) is left untouched — future XLS-imported admin expenses will still be created as unsettled debts. Not being changed in this pass.
- Wheel prize costs (`adminUsername = "Wheel"`, 3 separate creation points) — a pseudo-admin bucket, not a real wallet holder, untouched.
- Agent rake-share fees (`expenseType = "AGENT"`) — has its own separate `AgentSettlement` mechanism, untouched.
- Ticket purchases — untouched per explicit decision.

## Backfill

Settle every currently-unsettled `admin_expenses` row system-wide (all admins), except: `admin_username = 'Wheel'`, `expense_type = 'AGENT'`, or `notes LIKE 'Ticket purchase:%'`. This includes rows originally created by the XLS "הוצאות" import (their creation code isn't changing, but the existing data still gets reclassified from debt to settled expense, consistent with the corrected understanding). Sets `paid_from_admin_username = admin_username`, `settled = true`, `settled_at = expense_date`, `settled_by = admin_username`.
