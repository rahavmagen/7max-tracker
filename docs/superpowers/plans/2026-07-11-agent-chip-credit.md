# Agent Free-Chip Credit + Wheel Backfill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Detect the free ("red") chips agents hand their players out of their blue-chip pool, surface them as credit so player P&L and club profit stop overstating winnings, show it on the existing Agents page, and back-fill history.

**Architecture:** The agent chip-credit is booked as **real `creditTotal`** on agent players. Correction to an earlier assumption: the daily GG report does NOT overwrite `creditTotal` — `parseCreditSheet` (ReportService:299) returns early because GG reports have no credit sheet. The only writer of `creditTotal` from a sheet is the management-XLS import, which was a one-time initial upload. So booking persists. Because everything already keys off `creditTotal` (player dashboard P&L = `chips − creditTotal`, profit מאזן sums `creditTotal`), booking is all that's needed — no derived plumbing. The wheel backfill is a separate re-import pass (the wheel-detection fix already landed in ReportService).

**Tech stack:** Spring Boot (Java 21) backend, React (Vite) frontend, PostgreSQL. No unit-test harness exists → verification is `mvnw compile` + `npm run build` + SQL reconciliation queries + manual check.

**Core rule (per player under an agent):**
```
creditTotal = max(0, currentChips − gameP&L)      // free chips, booked as real credit
```
where `currentChips` = `player.currentChips` (Member Balance import), `gameP&L` = Σ AgentService.pnlOf(result) over ALL the player's results. Agent players get NO manual credit, so their `creditTotal` is entirely agent free chips. After booking, site P&L = `chips − creditTotal = gameP&L` (correct). Flag (`reconciles=false`) and do NOT write when the residual is negative or the player has withdrawals — surface for review instead.

**Reconciliation invariant (the user's rule):** `gameP&L + agentChipCredit (+ creditTotal + deposits) == currentChips`. Any player where the residual is negative, or has chips but no games, is flagged.

---

## Files

- Modify `src/main/java/com/sevenmax/tracker/service/AgentService.java` — add `agentChipCredit(player, gameP&L)` helper; add `currentChips`, `agentChipCredit`, `reconciles` to `getPlayerStats`; add `freeCreditTotal` per agent + `grandFreeCreditTotal` to `getAllAgentsSummary`.
- Modify `src/main/java/com/sevenmax/tracker/controller/AgentController.java` — surface the grand total in the `GET /api/agents` response wrapper (if summary returns a bare list today, wrap it `{ agents:[…], grandFreeCreditTotal }` OR add a sibling field — see Task 3).
- Modify `poker-frontend/src/pages/Agents.jsx` — Free Credit column + grand total on the summary table; Chips + Free Credit columns + ⚠ flags in the detail table.
- Modify `poker-frontend/src/pages/PlayerDetail.jsx` — show agent chip-credit and the corrected P&L.
- Modify `poker-frontend/src/pages/TotalProfit.jsx` — add Σ agentChipCredit into the מאזן credit side so club profit self-corrects.
- New: `scratchpad/backfill_reports.*` (or a controller endpoint) — re-import the 114 stored report blobs through the fixed pipeline.

---

## Task 1: Backend — agentChipCredit helper + per-player fields

**File:** `AgentService.java` (near `getPlayerStats`, which already computes `periodPnl` via `pnlOf`).

- [ ] **Step 1 — add the helper.** In `getPlayerStats`, after `periodPnl` is computed for a player, compute:
```java
BigDecimal currentChips = player.getCurrentChips() != null ? player.getCurrentChips() : BigDecimal.ZERO;
BigDecimal booked = player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO;
BigDecimal agentChipCredit = currentChips.subtract(booked).subtract(periodPnl); // residual free chips
boolean reconciles = agentChipCredit.compareTo(BigDecimal.ZERO) >= 0;
if (!reconciles) agentChipCredit = BigDecimal.ZERO; // don't create negative credit; flag instead
```
Note: `periodPnl` here is date-filtered. For the credit calc use the player's **all-time** game P&L (credit is a lifetime position), so compute a separate `lifetimePnl` = Σ pnlOf over ALL the player's results (unfiltered), not the period-filtered `periodPnl`. Add that sum.

- [ ] **Step 2 — expose fields.** Add to the per-player map: `m.put("currentChips", currentChips); m.put("agentChipCredit", agentChipCredit); m.put("reconciles", reconciles);`

- [ ] **Step 3 — compile.** `cd c:/projects/tracker && ./mvnw -q compile` → EXIT 0.

## Task 2: Backend — per-agent + grand total in summary

**File:** `AgentService.getAllAgentsSummary`.

- [ ] **Step 1.** For each agent, sum `agentChipCredit` across that agent's players (reuse the Task 1 formula per player) → `freeCreditTotal`; `m.put("freeCreditTotal", freeCreditTotal)`.
- [ ] **Step 2.** Accumulate a `grandFreeCreditTotal` across all agents.
- [ ] **Step 3 — controller.** In `AgentController.getAllAgents`, return `{ "agents": <list>, "grandFreeCreditTotal": <sum> }` (frontend Task 4 reads both). Update the frontend `getAgents` consumer accordingly.
- [ ] **Step 4 — compile.** EXIT 0.

## Task 3: Frontend — Agents summary table

**File:** `poker-frontend/src/pages/Agents.jsx`.

- [ ] Add a **Free Credit** column (header, per-row `fmt(a.freeCreditTotal)`, highlight when `> 0`), a Total-row cell summing it, and a page-level banner/line showing **grandFreeCreditTotal** ("Total credit given by agents: ₪X"). Bump empty-state colSpan.
- [ ] Adjust `getAgents` response handling for the new `{ agents, grandFreeCreditTotal }` shape.
- [ ] `npm run build` → EXIT 0.

## Task 4: Frontend — Agents detail table

**File:** `poker-frontend/src/pages/Agents.jsx` (detail per-player table) + `components/AgentPlayerRow.jsx`.

- [ ] Add **Chips** and **Free Credit** columns to the per-player detail table; render `⚠` when `p.reconciles === false` (chips don't reconcile to P&L + credit). Update the Total row and colSpans.
- [ ] `npm run build` → EXIT 0.

## Task 5: Frontend — Player dashboard reflects the credit

**File:** `poker-frontend/src/pages/PlayerDetail.jsx`.

- [ ] Where the player's credit / P&L is shown, add the agent chip-credit line and show corrected P&L = `chips − creditTotal − agentChipCredit`. Source the value from the player endpoint (extend the player DTO with `agentChipCredit` computed the same way, or fetch via the agent stats). Keep the raw values visible so nothing is hidden.
- [ ] `npm run build` → EXIT 0.

## Task 6: Profit מאזן includes agent chip-credit

**File:** `poker-frontend/src/pages/TotalProfit.jsx` (+ backend profit-summary if the credit total is computed server-side).

- [ ] Add `+ Σ agentChipCredit` into the מאזן credit side so `Club Earning` no longer overstates by the unbooked free chips. Show it as its own line ("+ אשראי צ'יפים מסוכן / Agent chip credit").
- [ ] Reconcile: Club Earning before/after should DROP by exactly the total free-chip credit (that's the leak being closed). Record the number.
- [ ] `npm run build` → EXIT 0.

## Task 7: Wheel + credit historical backfill

**Goal:** re-run the 114 stored report blobs through the (already-fixed) import so past satellite refunds become wheel expenses, and the derived agent credit reflects corrected data.

- [ ] Add an admin endpoint `POST /api/reports/reprocess-all` (or a one-off script) that loads each `reports.file_data` in `period_start` order and runs the existing import parse path (the same one `upload` uses), idempotently (existing sourceRef guards already prevent duplicate transactions).
- [ ] Run it against the local DB. Capture: how many chip transfers were reclassified to wheel expenses.
- [ ] Reconcile a spot-check (e.g. the 07-08 report: Gal10 & eyal146 −53 now booked as `Wheel`, not credit).

## Task 8: Block manual credit for players who have an agent

**Files:** `PlayerService.java` (updateCredit path ~line 160-195), `poker-frontend/src/pages/Transfers.jsx` (Manual Credit form).

- [ ] **Backend guard:** in the manual-credit update, if `player.getAgent() != null` (and player is not itself the agent), reject with 400 `{"error":"Manual credit cannot be given to a player who has an agent — their credit comes from agent chips"}`.
- [ ] **Frontend guard:** in the Manual Credit `PlayerSelect`/submit, if the selected player has an agent, disable submit and show the same message.
- [ ] compile + build → EXIT 0.

## Safety: verify before writing (do Tasks 3–4 read-only FIRST)

Build the agent-screen columns (Tasks 3–4) reading a **computed, not-yet-written** `agentChipCredit` first, eyeball the numbers against real data + the reconciliation invariant, and only then flip on the booking (Task 1 actually setting `creditTotal`). This avoids writing wrong credit to every agent player.

---

## Self-review notes
- **creditTotal clobber:** handled — agentChipCredit is derived, never written to creditTotal.
- **Idempotency:** derived values need none; the backfill reuses existing sourceRef dedup.
- **Negative residual:** floored to 0 and flagged (`reconciles=false`) rather than creating negative credit.
- **Open decision for execution:** if the user later wants the credit *stored* (not derived), add a dedicated `Player.agentChipCredit` column set during import — but derived is the safe default and satisfies "show on the dashboard."
