# Agent Running-Balance Ledger — Design

**Date:** 2026-07-15
**Status:** Design (awaiting review)

## Problem

Today the Agents page shows a **"pending balance"** = the sum of the agent's *rakeback share* (`agentRakeShare`) over their players' **unsettled** game results. It is one-directional (club → agent, rakeback only) and resets to zero when "Settle" is pressed. It does **not** reflect the true money position with an agent because it ignores:

- the players' net P&L that settles through the agent, and
- money already paid to (or received from) the agent.

The club reconciles with each agent roughly **every two weeks**. If the balance is small the agent may leave it for the next cycle (partial payment / carryover). The current all-or-nothing "Settle" cannot express that. We need one honest **running balance** per agent.

## The balance formula (agreed)

Positive = **club owes agent**; negative = **agent owes club**.

```
Current balance =  opening balance (set at a baseline date)
                +  rakeback   from games AFTER the baseline date   (agentRakeShare)
                +  players' net P&L from games AFTER the baseline date   (pnlOf)
                -  payments   logged AFTER the baseline date   (cash club → agent)
```

- **Rakeback**: the agent's cut of the club rake their players generated (e.g. players make ₪100 rake, agent at 50% → +₪50 owed to agent).
- **Players' net P&L**: sum of the agent's players' P&L. Players **win** → club owes agent more; players **lose** → agent owes the club their losses. (Worked example "Dan": rakeback +500, players −300, paid 200 → balance **0**.)
- **`pnlOf`**: for `MTT/SNG/AoF/SPIN_GOLD` = `resultAmount − buyIn`; otherwise `resultAmount` (same definition already used on the agent pages as `periodPnl`).
- Only the agent's **current** players count (`findAllByAgentId`), consistent with the existing agent pages.

### Why a baseline is required

Player chips/P&L accumulate over months, so an all-time sum would distort today's position. The admin sets **"as of [date], the balance with this agent is [₪X]"** once (the number they already know). Everything before that date is captured in the opening number; only games/payments **dated after** the baseline are counted, so there is no double-count.

### Re-baselining = the settlement checkpoint

The **newest** opening entry always wins. Every two weeks, after agreeing on the number, the admin can either just **log the payment** (remainder carries automatically), or **set a new opening balance** (today's date, = the agreed carried amount) for a clean slate and an audit trail of "we agreed on X on this date."

## Robustness to the daily XLS workflow

Reports are uploaded daily and sometimes deleted + re-uploaded to fix errors. `deleteReport` removes that report's `game_sessions` + `game_results`. Because the balance's accrued part is **computed live** from `game_results` (never snapshotted), correcting a report auto-corrects the balance. The **only stored anchors** are the opening baseline and the payments — both independent of reports, so they survive any upload churn.

**Edge case:** the balance only counts games dated *after* an agent's baseline. A correction to a report *before* the baseline will not move the balance (that period is locked inside the opening number). In practice corrections are to recent (post-baseline) reports. If a pre-baseline correction ever mattered, the admin adjusts the opening balance.

## Data model

New entity **`AgentLedgerEntry`** (table `agent_ledger_entry`):

| field | type | notes |
|---|---|---|
| `id` | Long | PK |
| `agent` | Player (ManyToOne, `@JsonIgnore`) | + serialized `agentId` |
| `type` | enum `OPENING` \| `PAYMENT` | |
| `effectiveDate` | LocalDate | the baseline date, or the payment date |
| `amount` | BigDecimal(14,2) signed | OPENING: opening balance (+ = club owes agent). PAYMENT: cash **club → agent** (+ = we paid him; − = he paid us) |
| `notes` | String | free text |
| `createdBy` | String | admin username |
| `createdAt` | LocalDateTime | |

No stored balance — the balance is always derived.

## Balance computation (service)

`AgentService.getAgentBalance(agentId)` returns:

```
baseline   = latest OPENING entry (max effectiveDate, tie-break createdAt); if none → openingBalance 0, D0 = null (count all-time)
rakebackSince = Σ agentRakeShare  over the agent's players' games with session date > D0
playerPnlSince = Σ pnlOf          over the agent's players' games with session date > D0
paymentsSince  = Σ amount         over PAYMENT entries with effectiveDate > D0
currentBalance = openingBalance + rakebackSince + playerPnlSince − paymentsSince
```

Returns `{ openingDate, openingBalance, rakebackSince, playerPnlSince, paymentsSince, currentBalance }` so the UI can show the full breakdown.

`getAllAgentsSummary` gains a `currentBalance` field per agent (replaces the displayed `pendingBalance`).

## Endpoints (admin-only)

- `GET  /api/agents/{id}/balance` — the breakdown above.
- `GET  /api/agents/{id}/ledger` — list of ledger entries (openings + payments), newest first.
- `POST /api/agents/{id}/ledger/opening` — `{ amount, effectiveDate, notes }` set/re-set opening balance.
- `POST /api/agents/{id}/ledger/payment` — `{ amount, effectiveDate, notes }` log a payment (signed).
- `DELETE /api/agents/ledger/{entryId}` — remove a mistaken entry.

## UI (Agents page + agent detail)

- **Agents summary table**: replace the "Pending balance" column with **"Current balance"** (from the ledger), colour-coded (green = club owes, red = agent owes).
- **Agent detail**: a balance card showing the breakdown — `opening (date) + rakeback + player P&L − payments = current balance`.
- **"Set opening balance"** button → amount + date + note.
- **"Log payment"** button → amount (with a to-agent / from-agent toggle that sets the sign) + date + note.
- **Ledger history** list with a delete affordance per entry.

## Explicitly out of scope (future)

- **Accounting/wallet integration.** Payments do **not** auto-create club-wallet or AdminExpense entries yet; this ledger is a standalone operational tracker. Wiring payments into the club wallet (and classifying the rakeback portion as a P&L expense vs. the player-P&L portion as a balance-sheet chip settlement) is a follow-up, tied to the paused accounting design.
- **Retiring the old `Settle` / `AgentSettlement`.** Existing settlement history stays readable; the new ledger becomes the source of truth for the balance. Removing the old settle flow is a later cleanup.

## Testing

Unit tests for `getAgentBalance`:
- Dan example (rakeback +500, players −300, paid 200 → 0).
- Players win (rakeback +500, players +300, paid 200 → +600).
- Carryover (partial payment leaves remainder).
- Re-baseline (newer OPENING supersedes; older games/payments ignored).
- Payment signs (club→agent reduces; agent→club increases).
- No baseline → all-time accrual, opening 0.
