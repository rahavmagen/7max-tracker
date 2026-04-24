# Cash Game League — Design Spec

**Date:** 2026-04-24
**Status:** Approved

---

## Overview

A league system for cash game sessions. The admin selects which sessions count toward the league and assigns per-session multipliers. Players accumulate points based on hands played and profit/loss. A live leaderboard is visible to all players.

---

## Scope

- One active league at a time
- Admin-only configuration: session selection, multipliers, minimum hands threshold
- Standings visible to all players
- No prizes or bracket management — pure points leaderboard

---

## Scoring Formula

For each selected session, per player:

```
hands_points  = hands_played × session.hands_multiplier
profit_points = result_amount_ILS × session.profit_multiplier
```

Negative profit subtracts points (losing sessions reduce the total).

Totals across all selected sessions:

```
total_hands_points  = SUM(hands_points per session)
total_profit_points = SUM(profit_points per session)
total_points        = total_hands_points + total_profit_points
```

Players with `total_hands < min_hands` are excluded from ranking (shown dimmed at the bottom).

---

## Data Model

### New table: `league_session_config`

| Column              | Type          | Description                              |
|---------------------|---------------|------------------------------------------|
| id                  | BIGINT PK     |                                          |
| game_session_id     | BIGINT FK     | References `game_sessions.id`            |
| included            | BOOLEAN       | Whether this session counts in the league|
| hands_multiplier    | INTEGER       | Points per hand played (default 1)       |
| profit_multiplier   | INTEGER       | Points per ₪ profit (default 1)          |
| created_at          | TIMESTAMP     |                                          |
| updated_at          | TIMESTAMP     |                                          |

One row per session that the admin has ever touched. Sessions not in this table are not in the league.

### New table: `league_config`

| Column      | Type    | Description                              |
|-------------|---------|------------------------------------------|
| id          | BIGINT  | Always a single row (id=1)               |
| min_hands   | INTEGER | Minimum hands to appear on leaderboard   |
| updated_at  | TIMESTAMP |                                        |

Single-row config table. Created with defaults on first use.

---

## Backend

### Endpoints

#### `GET /api/league/sessions`
Returns all cash game sessions with their league config (if any). Supports query params:
- `gameType` — filter by game type (NLH, PLO, etc.)
- `dateFrom`, `dateTo` — filter by session start date (YYYY-MM-DD)

Response per session:
```json
{
  "sessionId": 42,
  "tableName": "שולחן 7MAX NLH 1-2",
  "gameType": "NLH",
  "startTime": "2026-04-22T20:00:00",
  "playerCount": 14,
  "included": true,
  "handsMultiplier": 2,
  "profitMultiplier": 4
}
```

#### `POST /api/league/config`
Admin-only. Saves the full league config in one call.

Request body:
```json
{
  "minHands": 100,
  "sessions": [
    { "sessionId": 42, "included": true, "handsMultiplier": 2, "profitMultiplier": 4 },
    { "sessionId": 38, "included": false }
  ]
}
```

#### `GET /api/league/standings`
Returns computed standings. No auth required (visible to all).

Response:
```json
{
  "minHands": 100,
  "sessionCount": 3,
  "standings": [
    {
      "playerId": 7,
      "username": "AlonK",
      "totalHands": 342,
      "handsPoints": 1840,
      "profitILS": 600,
      "profitPoints": 2400,
      "totalPoints": 4240,
      "qualified": true,
      "rank": 1
    }
  ]
}
```

Standings are computed live by joining `game_results` with `league_session_config` — not stored.

### Auth
- `GET /api/league/sessions` — ADMIN / MANAGER only
- `POST /api/league/config` — ADMIN / MANAGER only
- `GET /api/league/standings` — public (any authenticated user)

---

## Frontend

### New page: `/league`

Two sections on one page:

**Top — Admin config** (hidden from regular players):
- Global min hands input
- Filter row: Game type dropdown, Date from, Date to, Clear button, session count badge
- Session table: checkbox, session name, game type badge, date, player count, hands × input, profit × input
- Action buttons: "Unmark All", "Save & Recalculate"

**Bottom — Standings** (visible to all):
- Summary line: `N sessions · min X hands · computed live`
- Standings table: Rank, Player, Hands, Hands Pts, Profit (₪), Profit Pts, Total Points
- Players below min hands shown dimmed at the bottom, no rank, no points
- Profit (₪) and Profit Pts colored green for positive, red for negative

### Navigation
Add "League" link to the sidebar nav, accessible to all authenticated users.

---

## Error Handling

- Saving config with no sessions selected is valid (league is empty, standings shows no players)
- Sessions with no `game_results` rows still appear in the admin list (hands/profit will be 0)
- `handsMultiplier` / `profitMultiplier` default to 1 when not set by admin

---

## Out of Scope

- Multiple concurrent leagues
- Historical league archives
- Prize pool management
- Per-player exclusions
- Public (unauthenticated) access to standings
