# Agent System — Phase 1 Design

**Date:** 2026-05-16
**Status:** Approved
**Scope:** Phase 1 — percentage-based agents (club manages chips/money directly)
**Phase 2 note:** Agents managing their own players (net P&L per agent) is deferred but data model is structured to support it.

---

## Overview

Agents bring players to the club and receive a percentage of the rake those players generate. The club tracks the accumulated rake share per agent, and periodically pays the agent — recorded as an admin expense.

---

## 1. Data Model

### `players` table — 3 new columns

| Column | Type | Description |
|---|---|---|
| `is_agent` | boolean, default false | Marks this player as an agent |
| `agent_rake_percentage` | decimal | e.g. `0.30` = 30%. Only relevant when `is_agent=true` |
| `agent_id` | FK → `players.id` | The agent this player belongs to. Null if no agent. Self-referential. |

### `game_results` table — 2 new columns

| Column | Type | Description |
|---|---|---|
| `agent_rake_share` | decimal, nullable | Calculated once at XLS upload time: `rake_paid × agent.agent_rake_percentage`. Null if player had no agent at upload time. Never recalculated after first set. |
| `agent_settlement_id` | FK → `agent_settlements.id`, nullable | Null until settled. Set when admin triggers settlement. |

### New table: `agent_settlements`

| Column | Type | Description |
|---|---|---|
| `id` | PK | |
| `agent_id` | FK → `players.id` | The agent being paid |
| `from_date` | LocalDate | Earliest game session date covered |
| `to_date` | LocalDate | Latest game session date covered |
| `total_rake` | decimal | Sum of `rake_paid` across all covered game results |
| `agent_share` | decimal | Sum of `agent_rake_share` across all covered game results — the actual payment amount |
| `admin_expense_id` | FK → `admin_expenses.id` | The expense created for this settlement |
| `created_at` | timestamp | |

### `admin_expenses` table — 2 new columns

| Column | Type | Description |
|---|---|---|
| `expense_type` | varchar, default `'ADMIN'` | Values: `'ADMIN'` (existing) or `'AGENT'` |
| `agent_settlement_id` | FK → `agent_settlements.id`, nullable | Links back to the settlement that created this expense |

---

## 2. Backend

### New entity: `AgentSettlement.java`
JPA entity mapping `agent_settlements` table. `@ManyToOne` to `Player` (agent), `@OneToOne` to `AdminExpense`.

### Modified entities
- `Player` — add `isAgent`, `agentRakePercentage`, `@ManyToOne agent` (self-referential, nullable)
- `GameResult` — add `agentRakeShare`, `@ManyToOne agentSettlement` (nullable)
- `AdminExpense` — add `expenseType` (default `"ADMIN"`), `@OneToOne agentSettlement` (nullable)

### New service: `AgentService.java`

| Method | Description |
|---|---|
| `getAgentSummary(agentId)` | Returns pending balance (sum of unsettled `agentRakeShare`) + list of past settlements |
| `getAgentBreakdown(agentId, fromDate, toDate)` | Returns unsettled `GameResult` rows for agent's players, filtered by session date |
| `settleAgent(agentId)` | Finds all unsettled `GameResult` rows → creates `AgentSettlement` + `AdminExpense` (type=AGENT) → links them → marks all rows with `agent_settlement_id` |
| `getAllAgentsSummary()` | Returns all agents with their pending balances (for admin overview) |

### Modified: `ReportService.java`

**On each `GameResult` save** — after setting `rakePaid`:
```
if player.agentId != null AND gameResult.agentRakeShare == null:
    gameResult.agentRakeShare = rakePaid × agent.agentRakePercentage
```
Guard `agentRakeShare == null` prevents recalculation on re-upload.

**In XLS player loop (Club Overview sheet, columns D+E)** — for every player (not just new ones):
```
Read agentClubId (col D), agentNickname (col E)
If not blank:
    Look up agent player by clubPlayerId, fallback to username match
    If found AND player.agentId != agent.id → update player.agentId
If blank in XLS:
    Leave existing agentId unchanged (manual overrides preserved)
```

### New controller: `AgentController.java`

```
GET  /api/agents                      → list all agents with pending balance (admin)
GET  /api/agents/{id}/summary         → pending balance + settlement history
GET  /api/agents/{id}/breakdown       → game-by-game detail, optional ?from=&to= filter
POST /api/agents/{id}/settle          → trigger settlement
```

Agent users can only access their own `{id}`. Admins can access any.

---

## 3. UI

### 3a. `PlayerDetail.jsx` — admin additions
- Toggle: "This player is an agent" → reveals rake % input
- Dropdown: "Assigned agent" — list of players with `isAgent=true`, or "None". Manual override of XLS value.

### 3b. New page: `Agents.jsx` (admin nav)
Table of all agents:
```
Agent     | # Players | Pending Balance | Last Settlement | Action
AgentBob  | 3         | ₪135            | Apr 10          | [Settle & Pay]
AgentMike | 1         | ₪0              | Apr 15          | (disabled)
```
Clicking agent name opens their breakdown.

### 3c. Agent breakdown detail (within `Agents.jsx`)
Date-range filterable table:
```
Date    | Table   | Player   | Rake | Agent Share | Status
Apr 14  | שמח     | Player A | ₪100 | ₪30         | pending
Apr 14  | שמח     | Player B | ₪150 | ₪45         | pending
Apr 15  | מובטחים | Player A | ₪200 | ₪60         | pending
─────────────────────────────────────────────────────────
                              ₪450   ₪135
                                    [Settle & Pay]
```

### 3d. `AdminExpenses.jsx` — additions
New "Agent Fees" section below existing admin expenses section. Same table layout, same settled/paid flow. Filtered by `expense_type = 'AGENT'`.

### 3e. New page: `AgentPortal.jsx` (agent's own view)
Visible only to users with `isAgent=true`. Added to nav when logged in as agent.

Visible to logged-in users whose player record has `isAgent=true`. The frontend checks this flag from the `/api/auth/me` response (which already returns the player object). Nav link "Agent Portal" appears only for these users.

**Top:** Current pending balance (large)

**Settlement history table:**
```
Period           | Total Rake | Your Share | Status
Apr 1 – Apr 15   | ₪1,000     | ₪300       | PAID
Apr 16 – today   | ₪450       | ₪135       | PENDING
```

**Players section:** List of agent's players with total rake per player, filterable by date.

**Game detail:** Expandable per row — game-by-game breakdown of rake contribution.

---

## 4. Phase 2 Considerations (deferred)

Phase 2 introduces agents who manage their own players (net P&L basis). The current design supports this via:
- `agent_id` FK on `Player` — relationship already exists
- `AgentSettlement` table — can add `player_breakdown` JSON or child table per player
- `AgentService.getAgentBreakdown()` — already returns per-player rake data
- Per-player rake percentage override will be added to `Player` in Phase 2 (currently one % per agent)

---

## 5. Files to Create/Modify

### Backend (`c:/projects/tracker`)
| Action | File |
|---|---|
| Create | `entity/AgentSettlement.java` |
| Create | `repository/AgentSettlementRepository.java` |
| Create | `service/AgentService.java` |
| Create | `controller/AgentController.java` |
| Modify | `entity/Player.java` |
| Modify | `entity/GameResult.java` |
| Modify | `entity/AdminExpense.java` |
| Modify | `service/ReportService.java` |

### Frontend (`c:/projects/poker-frontend`)
| Action | File |
|---|---|
| Create | `src/pages/Agents.jsx` |
| Create | `src/pages/AgentPortal.jsx` |
| Modify | `src/pages/PlayerDetail.jsx` |
| Modify | `src/pages/AdminExpenses.jsx` |
| Modify | `src/App.jsx` (routes + nav) |
