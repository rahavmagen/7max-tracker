# Inactive-Players CRM — Design

**Date:** 2026-08-05
**Goal:** Turn the existing on-demand *Inactive Players* report into a lightweight re-engagement CRM: persist an outreach status per player, let an admin mark a player "handled" (with a note), hide handled players for a cooldown, and send a weekly WhatsApp nudge with the current call-list.

## Background — what already exists

- Backend: `GET /api/reports/inactive-players` (`ReportController`) with params `recentDays` (silent period, default 7), `lookbackDays` (30), `minSessions`, `gameType`; query `GameResultRepository.getInactivePlayers(...)`.
- Frontend: `src/pages/InactivePlayers.jsx` — parameter boxes + on-demand table (Username, Full Name, Sessions, Last played).

## Decisions (agreed)

| Decision | Choice |
|---|---|
| When a handled player reappears | **After a cooldown** — hidden N days, then reappears if still inactive |
| Default cooldown | **7 days** (editable in the box) |
| What we capture when handling | **Done + note** (auto-stamped who + when) |
| Weekly job action | **WhatsApp nudge** (count + link) |
| Weekly criteria source | **Saved from the page** ("Save as weekly criteria" button) |
| Schedule | **Sunday 10:00 Asia/Jerusalem** |
| Recipients | **Same 3 numbers as KashCash** (`NOTIFY_WHATSAPP`) |

## Data model

**New table `player_outreach`** (an event log — one row per contact, so we keep history):
- `id` (PK)
- `player_id` (FK → players)
- `handled_at` (timestamp)
- `handled_by` (username from auth)
- `note` (text, nullable)

Cooldown = "is the player's most-recent `handled_at` within `cooldownDays` of now". JPA `ddl-auto=update` creates it on restart.

**New singleton table `inactive_report_config`** (the weekly criteria):
- `id` (PK, always 1)
- `recent_days`, `lookback_days`, `min_sessions` (int)
- `game_type` (varchar, nullable = all)
- `cooldown_days` (int)
- `updated_by`, `updated_at`

*Rejected alternative:* a single `reviewed` boolean on `players` — simpler but loses contact history and can't drive a cooldown cleanly.

## Report behaviour

1. Query finds inactive players as today (unchanged core query).
2. Load each candidate's latest `handled_at` from `player_outreach`.
3. **Exclude** players whose latest `handled_at` is within `cooldownDays`. Once the cooldown passes and they're still inactive, they reappear automatically; handling again inserts a new row (fresh cooldown).
4. For players who resurface after a past contact, return `lastHandledAt` + `lastNote` so the UI can show "Last contacted: 12/07/2026 by Rahav — 'said he'll come Friday'".

## API

- `GET /api/reports/inactive-players` — add `cooldownDays` param; each row also returns `lastHandledAt`, `lastHandledBy`, `lastNote`. Within-cooldown players excluded server-side.
- `POST /api/reports/inactive-players/{playerId}/handle` body `{note}` — inserts an outreach row (`handled_by` from `Authentication`).
- `GET /api/reports/inactive-report-config` — returns the saved weekly criteria (or defaults 7/30/10/all/7 if none).
- `PUT /api/reports/inactive-report-config` body `{recentDays, lookbackDays, minSessions, gameType, cooldownDays}` — upsert the singleton.

## Weekly scheduled job

`InactiveOutreachScheduler` with `@Scheduled(cron = "0 0 10 * * SUN", zone = "Asia/Jerusalem")`:
1. Read `inactive_report_config`.
2. Compute the list with the same cooldown-aware logic.
3. If count > 0, send WhatsApp via `WhatsAppService` to `NOTIFY_WHATSAPP`:
   `"📋 {n} players to re-engage this week (silent {recentDays}d, ≥{minSessions} sessions). Open: {frontendUrl}/inactive-players"`.

## Frontend changes (`InactivePlayers.jsx`)

- Load `inactive-report-config` on mount to prefill the boxes.
- Add a **Cooldown (days)** box.
- **"Save as weekly criteria"** button → `PUT inactive-report-config`; show a small confirmation + a status line: *"Weekly WhatsApp: Sunday 10:00 → 3 numbers"*.
- Results table gains an action column: **"Mark handled"** button + a note input (small inline prompt/modal). On success, optimistically drop the row.
- Resurfaced rows show their last-contacted line.

## Out of scope (YAGNI for v1)

- Structured outcomes (Reached / No answer / …) — plain note only for now.
- Per-outcome cooldowns.
- Weekly snapshot history / assignment of players to specific admins.
- "Show handled" toggle (can add later; MVP just hides within-cooldown).
