# Agent Page — Total Chips Column

## Problem

The Agents page shows player counts, rake, and P&L per agent, but no chip totals — so there's no way to see how many chips an agent's players are currently holding without cross-referencing Total Profit's "agent-held chips" figure manually.

## What's already there

Per-player chip data (`currentChips`) is already returned by `getAgentPlayerStats` and already summed client-side into `tChips` in `Agents.jsx` (line 691) — just never rendered. The main agents list (`getAgentsSummary`) has no chips field at all yet.

## Fix

### Backend (`AgentService.getAllAgentsSummary`)
Add `totalChips` to each agent's summary map: the agent's own `currentChips` (if they're also a player) plus the sum of their players' `currentChips` — mirroring the existing `isUnmanagedAgentSide` exclusion in `TotalProfit.jsx`, including its `!chipsStale` filter, so this new figure reconciles with Total Profit's "agent-held chips" number for non-club-managed agents.

### Frontend
- `AgentPlayerRow.jsx`: add a "Chips" column showing `player.currentChips` (already present in the data), plus the corresponding total in that table's Total row.
- `Agents.jsx` main summary table: add a "Chips" column per agent (`a.totalChips`, new backend field) and a grand-total row.
- Club-managed agents table and the "Expand All Agents" per-agent totals: same "Chips" column added, for consistent visibility everywhere on the page (per explicit decision — not trying to match Total Profit's exclusion in every table, just showing chip data wherever the page already shows a table).

## Scope

- No changes to Total Profit's calculation itself — this is a read-only display addition elsewhere that happens to use the same underlying data and exclusion logic for the main (non-club-managed) table, so the numbers agree.
- No changes to how chips are tracked, updated, or synced.
