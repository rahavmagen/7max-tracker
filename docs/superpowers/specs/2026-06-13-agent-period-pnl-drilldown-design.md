# Agent Box — Period P&L & Per-Player Game Drill-Down

**Date:** 2026-06-13
**Status:** Approved
**Builds on:** [2026-05-16-agent-system-design.md](2026-05-16-agent-system-design.md) (Agent System Phase 1)

---

## Overview

Today, an agent's "Players" table (in both the admin `Agents.jsx` Detail panel and the agent's own `AgentPortal.jsx`) shows Games / Club Rake / Agent Share for the selected date range, plus a static `Balance` snapshot (`currentChips - creditTotal`, not date-filtered) on the admin view.

Two problems:

1. **Profit visibility**: There's no way to see what a player actually *won or lost* (from the XLS `resultAmount`, tournament-adjusted) for the selected period — only rake-derived numbers. Agents distributing chips to players without matching credit makes the chip-based `Balance` an unreliable proxy for "did this player actually play/win."
2. **No drill-down**: To see a player's individual games, you have to leave this page and go to their `PlayerDetail` dashboard.

This feature adds a **"Period P&L"** column (sum of tournament-adjusted `resultAmount` for the date range) and an **inline expandable row** per player showing their game-by-game results — on both `Agents.jsx` (admin) and `AgentPortal.jsx` (agent's own view). It also fixes the date-range filter, which currently appears not to affect the displayed results on these pages (and on `PlayerDetail.jsx`'s Game Results tab).

---

## 1. Backend — extend `AgentService.getPlayerStats`

No new endpoint. `GET /api/agents/{id}/player-stats?from=&to=` (used by both `AgentPortal.jsx` and `Agents.jsx`) already groups each player's `GameResult`s by the `[from, to]` date range (`AgentService.java:190-197`). Add two fields to each player's response map:

- **`periodPnl`** (BigDecimal) — sum over that player's filtered `GameResult`s of:
  - `resultAmount - buyIn` if `session.gameType` is a tournament type (`MTT`, `SNG`, `AoF`, `SPIN_GOLD`)
  - `resultAmount` otherwise

- **`games`** (array) — one entry per filtered `GameResult`, sorted by `session.startTime` descending:
  ```
  { date, gameType, pnl, buyIn, cashout, rakePaid }
  ```
  `pnl` computed the same way as `periodPnl` per-row. `date` is `session.startTime` (ISO).

No entity/migration changes — all source fields (`resultAmount`, `buyIn`, `cashout`, `rakePaid`, `session.gameType`, `session.startTime`) already exist on `GameResult`/`GameSession`.

---

## 2. Frontend — `AgentPortal.jsx` and `Agents.jsx` Detail panel

Both Players tables get the same treatment (Agents.jsx additionally keeps its existing `Balance` column):

- **New "Period P&L" column** — placed after Agent Share / Your Share. Colored red/green like other P&L values (reuse `balanceClass` helper pattern from `PlayerDetail.jsx`). Total row sums it across all players.
- **Row expand** — each player row gets a chevron toggle. Expanding inserts a sub-table beneath the row with columns: **Date | Game Type | P&L | Buy-in | Cashout | Rake**, populated from that player's `games` array. If `games` is empty, show "No games in this period."
- **Expand All / Collapse All** — buttons placed next to the From/To date filters, toggling all rows at once.
- Expand/collapse state is local UI state (not persisted), reset when `from`/`to` changes and stats are refetched.

Since `Agents.jsx`'s Players table is structurally the same as `AgentPortal.jsx`'s (plus the Balance column), the row-rendering + expand logic should be written once and reused (e.g. a small shared component or shared render function) to avoid duplicating the sub-table markup.

---

## 3. Date-filter fix (bundled into this work)

The From/To filters on `AgentPortal.jsx`, `Agents.jsx`, and `PlayerDetail.jsx`'s Game Results tab are reported as "not working," but static review of `DateInput.jsx`, `dates.js`, and the filter logic in all three pages looks logically correct (ISO string comparisons, correct `displayToIso`/`isoToDisplay` round-trip).

Since this work already touches all three pages, before implementing the new column/drill-down:

1. Reproduce live in the browser — set From/To on each page, compare network request params vs. displayed results.
2. Most likely root cause: a timezone shift between `session.startTime` (stored as UTC instant) and `.toLocalDate()` / `.substring(0,10)` date-string comparisons, causing sessions to fall on the "wrong side" of a date boundary. Confirm with evidence before fixing (per systematic-debugging — no fix without root cause).
3. Fix at the root (likely in how session dates are compared/extracted), not by patching each page's filter independently.

---

## 4. Deferred / not in this spec

The original ask for a flag to apply an XLS-based profit metric to *all* players (not just agents) on the main Profit page is **not** part of this work. Once `PlayerDetail.jsx`'s Game Results filter + Total are fixed (Section 3), that page already gives any player — agent or not — an XLS-based P&L for an arbitrary period, via the existing Total row. Revisit only if a dedicated flag/toggle is still wanted after using this in practice.

---

## 5. Files to Create/Modify

### Backend (`c:/projects/tracker`)
| Action | File |
|---|---|
| Modify | `service/AgentService.java` — `getPlayerStats()`: add `periodPnl` + `games` |

### Frontend (`c:/projects/poker-frontend`)
| Action | File |
|---|---|
| Modify | `src/pages/AgentPortal.jsx` — Period P&L column, row expand, Expand/Collapse All |
| Modify | `src/pages/Agents.jsx` — same, in Detail panel Players table |
| Modify | `src/pages/PlayerDetail.jsx` — date-filter fix (Section 3) |
| Possibly modify | `src/utils/dates.js` or session-date handling — date-filter root cause fix (Section 3) |
