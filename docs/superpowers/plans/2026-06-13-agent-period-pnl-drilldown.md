# Agent Box — Period P&L & Per-Player Game Drill-Down Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Period P&L" column and a per-player, per-game drill-down to the Players tables on `AgentPortal.jsx` (agent's own view) and `Agents.jsx` (admin Detail panel), backed by an extension to `AgentService.getPlayerStats`.

**Architecture:** No new endpoint. `AgentService.getPlayerStats(agentId, from, to)` already groups each player's `GameResult`s by the `[from, to]` date range — it gains two new fields per player: `periodPnl` (sum of tournament-adjusted `resultAmount` over that range) and `games` (one row per `GameResult` in the range, sorted by date descending). On the frontend, a new shared `AgentPlayerRow` component renders one player row plus an optional expandable sub-table of `games`; both `AgentPortal.jsx` and `Agents.jsx` swap their existing inline `<tr>` row-rendering for this component and gain Expand All / Collapse All controls and a Period P&L total.

**Tech Stack:** Spring Boot 3 / JPA (backend, port 8080, `local` profile, Spring DevTools auto-restart enabled), React 19 + Vite (frontend, port 5173), PostgreSQL 17 (local copy of prod).

---

## Before you start: date-range filter (spec Section 3)

The spec flagged the From/To date filters on `AgentPortal.jsx` / `Agents.jsx` / `PlayerDetail.jsx` as "reported not working" and asked for a root-cause investigation before building on top of them.

**That investigation is already done.** Static review of `DateInput.jsx`, `utils/dates.js`, and the filter logic in all three pages found no bug — date comparisons are consistent ISO-string / `LocalDate` comparisons on both ends. A live test against the local backend (player 1 assigned to agent 270, three different `from`/`to` ranges via `GET /api/agents/270/player-stats`) showed `gameCount` correctly changing from 32 → 5 → 0 as the range narrowed, proving the backend filter works end-to-end.

**Conclusion:** there is no filter bug to fix. The user's complaint is addressed by this feature itself — today the Players table shows only rake-derived numbers (Club Rake / Agent Share), which barely change with a date filter and look "static" even though they're correctly filtered. The new **Period P&L** column makes the date range's effect immediately visible (a player's P&L for last week looks very different from their all-time P&L). Task 6 below re-confirms this live as part of final verification — that *is* the resolution of spec Section 3, not a separate code fix.

---

## File Structure

| File | Responsibility |
|---|---|
| `tracker/src/main/java/com/sevenmax/tracker/service/AgentService.java` | Extend `getPlayerStats()` to compute `periodPnl` + `games` per player (Task 2) |
| `poker-frontend/src/components/AgentPlayerRow.jsx` (new) | Shared player row + expandable game drill-down table, used by both pages (Task 3) |
| `poker-frontend/src/pages/AgentPortal.jsx` | Period P&L column, Expand/Collapse All, row drill-down (Task 4) |
| `poker-frontend/src/pages/Agents.jsx` | Same, in the admin Detail panel's Players table (Task 5) |

---

## Task 1: Test data — assign a player to agent maxsimus7 (local DB only)

**Files:** None (local database row update only — no code changes, no commit for this task).

Agent "maxsimus7" (player id 270, `is_agent=true`, `agent_rake_percentage=0.30`) currently has zero players assigned in the local DB (`agent_id` is `NULL` for all players), so `getPlayerStats(270, ...)` returns an empty list. Player 1 ("gooshpanka") has 32 `game_results` spanning January–April 2026 and makes good test data.

- [ ] **Step 1: Assign player 1 to agent 270**

```bash
rtk "/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d poker_tracker -c "UPDATE players SET agent_id = 270 WHERE id = 1;"
```

(Set `PGPASSWORD=Pokerman1!` in the environment first, as in `CLAUDE.md`'s DB-copy instructions.)

Expected: `UPDATE 1`

- [ ] **Step 2: Baseline-verify via the existing endpoint (before Task 2's changes)**

```bash
rtk curl -s -H "Authorization: Bearer $(cat /c/temp/admin_jwt.txt)" \
  "http://localhost:8080/api/agents/270/player-stats?from=2026-04-15&to=2026-04-21"
```

Expected: a JSON array with one object for `playerId: 1`, `"username": "gooshpanka"`, `"gameCount": 5`, `"totalRake": 200.80`. This confirms the date filter is already working (per "Before you start" above) and gives us the baseline shape before Task 2 adds `periodPnl`/`games`.

---

## Task 2: Backend — extend `AgentService.getPlayerStats` with `periodPnl` + `games`

**Files:**
- Modify: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/AgentService.java`

No entity or migration changes — `resultAmount`, `buyIn`, `cashout`, `rakePaid`, `session.gameType`, `session.startTime` all already exist on `GameResult`/`GameSession`. Imports are already sufficient (`com.sevenmax.tracker.entity.*` and `java.util.*` are wildcard-imported at lines 3 and 11, covering `GameSession`, `Set`, and `Collections`).

- [ ] **Step 1: Add the tournament-type constant and `pnlOf` helper**

In `AgentService.java`, find:

```java
        return settlement;
    }

    /** Per-player rake stats for an agent, with optional date filter (all results, settled+unsettled) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerStats(Long agentId, LocalDate from, LocalDate to) {
```

Replace with:

```java
        return settlement;
    }

    private static final Set<GameSession.GameType> TOURNAMENT_TYPES = Set.of(
        GameSession.GameType.MTT, GameSession.GameType.SNG, GameSession.GameType.AoF, GameSession.GameType.SPIN_GOLD
    );

    /** resultAmount, tournament-adjusted (resultAmount - buyIn) for MTT/SNG/AoF/SPIN_GOLD */
    private static BigDecimal pnlOf(GameResult gr) {
        BigDecimal resultAmount = gr.getResultAmount() != null ? gr.getResultAmount() : BigDecimal.ZERO;
        if (TOURNAMENT_TYPES.contains(gr.getSession().getGameType())) {
            BigDecimal buyIn = gr.getBuyIn() != null ? gr.getBuyIn() : BigDecimal.ZERO;
            return resultAmount.subtract(buyIn);
        }
        return resultAmount;
    }

    /** Per-player rake stats for an agent, with optional date filter (all results, settled+unsettled) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerStats(Long agentId, LocalDate from, LocalDate to) {
```

- [ ] **Step 2: Add `periodPnl` and `games` to each player's result map**

In the same method, find:

```java
        return agentPlayers.stream()
            .map(player -> {
                List<GameResult> rows = resultsByPlayer.getOrDefault(player.getId(), Collections.emptyList());
                BigDecimal totalRake = rows.stream()
                    .map(gr -> gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal agentShare = rows.stream()
                    .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("playerId", player.getId());
                m.put("username", player.getUsername());
                m.put("fullName", player.getFullName());
                m.put("balance", player.getBalance());
                m.put("gameCount", rows.size());
                m.put("totalRake", totalRake);
                m.put("agentShare", agentShare);
                return m;
            })
```

Replace with:

```java
        return agentPlayers.stream()
            .map(player -> {
                List<GameResult> rows = resultsByPlayer.getOrDefault(player.getId(), Collections.emptyList());
                BigDecimal totalRake = rows.stream()
                    .map(gr -> gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal agentShare = rows.stream()
                    .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal periodPnl = rows.stream()
                    .map(AgentService::pnlOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                List<Map<String, Object>> games = rows.stream()
                    .sorted((a, b) -> b.getSession().getStartTime().compareTo(a.getSession().getStartTime()))
                    .map(gr -> {
                        Map<String, Object> g = new LinkedHashMap<>();
                        g.put("date", gr.getSession().getStartTime().toString());
                        g.put("gameType", gr.getSession().getGameType().name());
                        g.put("pnl", pnlOf(gr));
                        g.put("buyIn", gr.getBuyIn());
                        g.put("cashout", gr.getCashout());
                        g.put("rakePaid", gr.getRakePaid());
                        return g;
                    })
                    .collect(Collectors.toList());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("playerId", player.getId());
                m.put("username", player.getUsername());
                m.put("fullName", player.getFullName());
                m.put("balance", player.getBalance());
                m.put("gameCount", rows.size());
                m.put("totalRake", totalRake);
                m.put("agentShare", agentShare);
                m.put("periodPnl", periodPnl);
                m.put("games", games);
                return m;
            })
```

- [ ] **Step 3: Wait for Spring DevTools to recompile, then verify live**

The local backend (port 8080, `local` profile) is already running with DevTools auto-restart. Poll until the new field appears (typically 5-15s):

```bash
for i in 1 2 3 4 5 6; do
  rtk curl -s -H "Authorization: Bearer $(cat /c/temp/admin_jwt.txt)" \
    "http://localhost:8080/api/agents/270/player-stats?from=2026-04-15&to=2026-04-21" | grep -q periodPnl && break
  sleep 5
done
```

- [ ] **Step 4: Verify the full response shape and values**

```bash
rtk curl -s -H "Authorization: Bearer $(cat /c/temp/admin_jwt.txt)" \
  "http://localhost:8080/api/agents/270/player-stats?from=2026-04-15&to=2026-04-21"
```

Expected, for the `playerId: 1` ("gooshpanka") entry:
- `"gameCount": 5`, `"totalRake": 200.80` (unchanged from Task 1's baseline)
- `"periodPnl": -3217.00`
- `"games"` is an array of 5 objects, each with `date`, `gameType`, `pnl`, `buyIn`, `cashout`, `rakePaid`
- The 5 `pnl` values sum to `-3217.00`
- `games` is sorted by `date` descending (first entry has the latest date)

- [ ] **Step 5: Commit**

```bash
rtk git add tracker/src/main/java/com/sevenmax/tracker/service/AgentService.java
rtk git commit -m "Add periodPnl and per-game breakdown to AgentService.getPlayerStats"
```

(Run from `c:/projects/tracker`.)

---

## Task 3: Frontend — shared `AgentPlayerRow` component

**Files:**
- Create: `c:/projects/poker-frontend/src/components/AgentPlayerRow.jsx`

This component renders one player's table row plus, when `expanded`, a second `<tr>` containing a sub-table of that player's `games`. It's used by both `AgentPortal.jsx` (`showBalance={false}`, 5 visible columns: Player / Games / Club Rake / Your Share / Period P&L) and `Agents.jsx` (`showBalance={true}`, 6 visible columns: Player / Balance / Games / Club Rake / Agent Share / Period P&L).

- [ ] **Step 1: Create the component**

```jsx
import { Link } from 'react-router-dom';
import { fmtDateOnly } from '../utils/dates';

const fmt = (n) => {
  if (n === undefined || n === null) return '₪0.00';
  const abs = Math.abs(Number(n)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (Number(n) < 0 ? '-' : '') + '₪' + abs;
};

const balanceClass = (n) => Number(n) > 0 ? 'positive' : Number(n) < 0 ? 'negative' : 'zero';

export default function AgentPlayerRow({ player, showBalance, expanded, onToggle }) {
  const colCount = showBalance ? 6 : 5;
  return (
    <>
      <tr onClick={onToggle} style={{ borderBottom: '1px solid #1e2235', cursor: 'pointer' }}>
        <td style={{ padding: '8px' }}>
          <span style={{ display: 'inline-block', width: '1.2em', color: '#64748b' }}>{expanded ? '▾' : '▸'}</span>
          <Link to={`/player/${player.playerId}`} onClick={e => e.stopPropagation()} style={{ color: '#60a5fa', textDecoration: 'underline' }}>
            {player.username}
          </Link>
          {player.fullName && <span style={{ color: '#64748b', fontSize: '0.8rem', marginLeft: '0.4rem' }}>{player.fullName}</span>}
        </td>
        {showBalance && (
          <td style={{ padding: '8px', textAlign: 'right', color: Number(player.balance) < 0 ? '#f87171' : '#4ade80', fontWeight: 600 }}>{fmt(player.balance)}</td>
        )}
        <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{player.gameCount}</td>
        <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{fmt(player.totalRake)}</td>
        <td style={{ padding: '8px', textAlign: 'right', color: '#fbbf24', fontWeight: 600 }}>{fmt(player.agentShare)}</td>
        <td style={{ padding: '8px', textAlign: 'right', fontWeight: 600 }} className={balanceClass(player.periodPnl)}>{fmt(player.periodPnl)}</td>
      </tr>
      {expanded && (
        <tr>
          <td colSpan={colCount} style={{ padding: 0, background: '#0d0f1a', borderBottom: '1px solid #1e2235' }}>
            {(!player.games || player.games.length === 0) ? (
              <div style={{ padding: '0.6rem 1rem', color: '#64748b', fontSize: '0.82rem' }}>No games in this period</div>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ color: '#64748b', textAlign: 'left', fontSize: '0.78rem' }}>
                    <th style={{ padding: '4px 1rem' }}>Date</th>
                    <th style={{ padding: '4px 8px' }}>Game</th>
                    <th style={{ padding: '4px 8px', textAlign: 'right' }}>Buy-in</th>
                    <th style={{ padding: '4px 8px', textAlign: 'right' }}>Cashout</th>
                    <th style={{ padding: '4px 8px', textAlign: 'right' }}>Rake</th>
                    <th style={{ padding: '4px 8px', textAlign: 'right' }}>P&L</th>
                  </tr>
                </thead>
                <tbody>
                  {player.games.map((g, i) => (
                    <tr key={i} style={{ fontSize: '0.82rem' }}>
                      <td style={{ padding: '4px 1rem', color: '#94a3b8', whiteSpace: 'nowrap' }}>{fmtDateOnly(g.date)}</td>
                      <td style={{ padding: '4px 8px' }}><span style={{ background: '#2d3148', padding: '2px 8px', borderRadius: '4px', fontSize: '0.78rem' }}>{g.gameType}</span></td>
                      <td style={{ padding: '4px 8px', textAlign: 'right', color: '#ef4444' }}>{fmt(-(g.buyIn || 0))}</td>
                      <td style={{ padding: '4px 8px', textAlign: 'right' }}>{fmt(g.cashout)}</td>
                      <td style={{ padding: '4px 8px', textAlign: 'right', color: '#f59e0b' }}>{fmt(g.rakePaid)}</td>
                      <td style={{ padding: '4px 8px', textAlign: 'right', fontWeight: 600 }} className={balanceClass(g.pnl)}>{fmt(g.pnl)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </td>
        </tr>
      )}
    </>
  );
}
```

- [ ] **Step 2: Lint check**

```bash
rtk npx eslint src/components/AgentPlayerRow.jsx
```

(Run from `c:/projects/poker-frontend`.) Expected: no errors. (No automated test suite exists in this codebase — this lint pass plus the live verification in Tasks 4-6 is the established verification convention here.)

- [ ] **Step 3: Commit**

```bash
rtk git add src/components/AgentPlayerRow.jsx
rtk git commit -m "Add shared AgentPlayerRow component (Period P&L + game drill-down)"
```

(Run from `c:/projects/poker-frontend`.)

---

## Task 4: Frontend — wire `AgentPlayerRow` into `AgentPortal.jsx`

**Files:**
- Modify: `c:/projects/poker-frontend/src/pages/AgentPortal.jsx`

- [ ] **Step 1: Import the new component and add `balanceClass`**

Find:

```jsx
import DateInput from '../components/DateInput';

const inputStyle = { background: '#1a1d2e', border: '1px solid #2d3148', color: '#e2e8f0', padding: '4px 8px', borderRadius: '5px', fontSize: '0.82rem' };

const fmt = (n) => {
  if (n === undefined || n === null) return '₪0.00';
  const abs = Math.abs(Number(n)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (Number(n) < 0 ? '-' : '') + '₪' + abs;
};
```

Replace with:

```jsx
import DateInput from '../components/DateInput';
import AgentPlayerRow from '../components/AgentPlayerRow';

const inputStyle = { background: '#1a1d2e', border: '1px solid #2d3148', color: '#e2e8f0', padding: '4px 8px', borderRadius: '5px', fontSize: '0.82rem' };

const fmt = (n) => {
  if (n === undefined || n === null) return '₪0.00';
  const abs = Math.abs(Number(n)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (Number(n) < 0 ? '-' : '') + '₪' + abs;
};

const balanceClass = (n) => Number(n) > 0 ? 'positive' : Number(n) < 0 ? 'negative' : 'zero';
```

- [ ] **Step 2: Add `expandedIds` state, reset it in `fetchStats`, and add toggle helpers**

Find:

```jsx
  const [statsLoading, setStatsLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchStats = (from, to) => {
    setStatsLoading(true);
    const params = {};
    if (from) params.from = from;
    if (to) params.to = to;
    getAgentPlayerStats(agentId, params)
      .then(r => { setPlayerStats(r.data); setStatsLoading(false); })
      .catch(() => { setPlayerStats([]); setStatsLoading(false); });
  };
```

Replace with:

```jsx
  const [statsLoading, setStatsLoading] = useState(false);
  const [error, setError] = useState(null);
  const [expandedIds, setExpandedIds] = useState(new Set());

  const fetchStats = (from, to) => {
    setStatsLoading(true);
    setExpandedIds(new Set());
    const params = {};
    if (from) params.from = from;
    if (to) params.to = to;
    getAgentPlayerStats(agentId, params)
      .then(r => { setPlayerStats(r.data); setStatsLoading(false); })
      .catch(() => { setPlayerStats([]); setStatsLoading(false); });
  };

  const toggleExpand = (playerId) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(playerId)) next.delete(playerId); else next.add(playerId);
      return next;
    });
  };

  const expandAll = () => setExpandedIds(new Set(playerStats.map(p => p.playerId)));
  const collapseAll = () => setExpandedIds(new Set());
```

- [ ] **Step 3: Add `statsTotalPnl`**

Find:

```jsx
  const statsTotalRake = playerStats.reduce((s, p) => s + Number(p.totalRake || 0), 0);
  const statsTotalShare = playerStats.reduce((s, p) => s + Number(p.agentShare || 0), 0);
```

Replace with:

```jsx
  const statsTotalRake = playerStats.reduce((s, p) => s + Number(p.totalRake || 0), 0);
  const statsTotalShare = playerStats.reduce((s, p) => s + Number(p.agentShare || 0), 0);
  const statsTotalPnl = playerStats.reduce((s, p) => s + Number(p.periodPnl || 0), 0);
```

- [ ] **Step 4: Add Expand All / Collapse All buttons next to the "Players (N)" label**

Find:

```jsx
        <div style={{ marginBottom: '1rem' }}>
          <strong style={{ color: '#e2e8f0' }}>Players ({playerStats.length})</strong>
          {(filterFrom || filterTo) && <span style={{ color: '#64748b', fontSize: '0.8rem', marginLeft: '0.75rem' }}>{filterFrom || '…'} – {filterTo || '…'}</span>}
        </div>
```

Replace with:

```jsx
        <div style={{ marginBottom: '1rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '0.5rem' }}>
          <div>
            <strong style={{ color: '#e2e8f0' }}>Players ({playerStats.length})</strong>
            {(filterFrom || filterTo) && <span style={{ color: '#64748b', fontSize: '0.8rem', marginLeft: '0.75rem' }}>{filterFrom || '…'} – {filterTo || '…'}</span>}
          </div>
          {playerStats.length > 0 && (
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button onClick={expandAll} style={{ padding: '4px 10px', borderRadius: '5px', border: '1px solid #2d3148', background: 'transparent', color: '#94a3b8', cursor: 'pointer', fontSize: '0.78rem' }}>Expand All</button>
              <button onClick={collapseAll} style={{ padding: '4px 10px', borderRadius: '5px', border: '1px solid #2d3148', background: 'transparent', color: '#94a3b8', cursor: 'pointer', fontSize: '0.78rem' }}>Collapse All</button>
            </div>
          )}
        </div>
```

- [ ] **Step 5: Add the "Period P&L" column header**

Find:

```jsx
                  <th style={{ padding: '8px', textAlign: 'right' }}>Your Share</th>
                </tr>
```

Replace with:

```jsx
                  <th style={{ padding: '8px', textAlign: 'right' }}>Your Share</th>
                  <th style={{ padding: '8px', textAlign: 'right' }}>Period P&L</th>
                </tr>
```

- [ ] **Step 6: Replace the row map with `AgentPlayerRow`, update colSpan, and add the Period P&L total**

Find:

```jsx
              <tbody>
                {playerStats.map(p => (
                  <tr key={p.playerId} style={{ borderBottom: '1px solid #1e2235' }}>
                    <td style={{ padding: '8px' }}>
                      <Link to={`/player/${p.playerId}`} style={{ color: '#60a5fa', textDecoration: 'underline' }}>
                        {p.username}
                      </Link>
                      {p.fullName && <span style={{ color: '#64748b', fontSize: '0.8rem', marginLeft: '0.4rem' }}>{p.fullName}</span>}
                    </td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{p.gameCount}</td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{fmt(p.totalRake)}</td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#fbbf24', fontWeight: 600 }}>{fmt(p.agentShare)}</td>
                  </tr>
                ))}
                {playerStats.length === 0 && <tr><td colSpan={4} style={{ padding: '1rem', color: '#64748b', textAlign: 'center' }}>No data for selected period</td></tr>}
                {playerStats.length > 0 && (
                  <tr style={{ borderTop: '1px solid #334155', background: '#12151f' }}>
                    <td style={{ padding: '8px', color: '#e2e8f0', fontWeight: 700 }}>Total</td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{playerStats.reduce((s, p) => s + p.gameCount, 0)}</td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8', fontWeight: 600 }}>{fmt(statsTotalRake)}</td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#fbbf24', fontWeight: 700 }}>{fmt(statsTotalShare)}</td>
                  </tr>
                )}
              </tbody>
```

Replace with:

```jsx
              <tbody>
                {playerStats.map(p => (
                  <AgentPlayerRow key={p.playerId} player={p} showBalance={false} expanded={expandedIds.has(p.playerId)} onToggle={() => toggleExpand(p.playerId)} />
                ))}
                {playerStats.length === 0 && <tr><td colSpan={5} style={{ padding: '1rem', color: '#64748b', textAlign: 'center' }}>No data for selected period</td></tr>}
                {playerStats.length > 0 && (
                  <tr style={{ borderTop: '1px solid #334155', background: '#12151f' }}>
                    <td style={{ padding: '8px', color: '#e2e8f0', fontWeight: 700 }}>Total</td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{playerStats.reduce((s, p) => s + p.gameCount, 0)}</td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8', fontWeight: 600 }}>{fmt(statsTotalRake)}</td>
                    <td style={{ padding: '8px', textAlign: 'right', color: '#fbbf24', fontWeight: 700 }}>{fmt(statsTotalShare)}</td>
                    <td style={{ padding: '8px', textAlign: 'right', fontWeight: 700 }} className={balanceClass(statsTotalPnl)}>{fmt(statsTotalPnl)}</td>
                  </tr>
                )}
              </tbody>
```

Note: the `Link` import on line 2 (`import { useNavigate, Link } from 'react-router-dom';`) is still used elsewhere on this page (none currently outside this block) — after this edit, `Link` becomes unused in `AgentPortal.jsx` itself since `AgentPlayerRow` now renders its own `Link`. Remove it from the import:

Find:

```jsx
import { useNavigate, Link } from 'react-router-dom';
```

Replace with:

```jsx
import { useNavigate } from 'react-router-dom';
```

- [ ] **Step 7: Lint check**

```bash
rtk npx eslint src/pages/AgentPortal.jsx
```

(Run from `c:/projects/poker-frontend`.) This file has 2 pre-existing errors unrelated to this change (`'navigate' is assigned a value but never used` and a `react-hooks/set-state-in-effect` error on the `useEffect`) plus 1 pre-existing `exhaustive-deps` warning — out of scope for this plan, do not fix them. Expected: the same 2 errors + 1 warning as before this task's edits, and nothing new referencing `AgentPlayerRow`, `balanceClass`, `Link`, or `colSpan`.

- [ ] **Step 8: Commit**

```bash
rtk git add src/pages/AgentPortal.jsx
rtk git commit -m "Add Period P&L column and game drill-down to AgentPortal players table"
```

(Run from `c:/projects/poker-frontend`.)

---

## Task 5: Frontend — wire `AgentPlayerRow` into `Agents.jsx` (admin Detail panel)

**Files:**
- Modify: `c:/projects/poker-frontend/src/pages/Agents.jsx`

- [ ] **Step 1: Import the new component and add `balanceClass`**

Find:

```jsx
import DateInput from '../components/DateInput';

const inputStyle = { background: '#1a1d2e', border: '1px solid #2d3148', color: '#e2e8f0', padding: '4px 8px', borderRadius: '5px', fontSize: '0.82rem' };

const fmt = (n) => {
  if (n === undefined || n === null) return '₪0.00';
  const abs = Math.abs(Number(n)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (Number(n) < 0 ? '-' : '') + '₪' + abs;
};
```

Replace with:

```jsx
import DateInput from '../components/DateInput';
import AgentPlayerRow from '../components/AgentPlayerRow';

const inputStyle = { background: '#1a1d2e', border: '1px solid #2d3148', color: '#e2e8f0', padding: '4px 8px', borderRadius: '5px', fontSize: '0.82rem' };

const fmt = (n) => {
  if (n === undefined || n === null) return '₪0.00';
  const abs = Math.abs(Number(n)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (Number(n) < 0 ? '-' : '') + '₪' + abs;
};

const balanceClass = (n) => Number(n) > 0 ? 'positive' : Number(n) < 0 ? 'negative' : 'zero';
```

- [ ] **Step 2: Add `expandedIds` state**

Find:

```jsx
  const [editingRake, setEditingRake] = useState(null); // agentId being edited
  const [rakeInput, setRakeInput] = useState('');
```

Replace with:

```jsx
  const [editingRake, setEditingRake] = useState(null); // agentId being edited
  const [rakeInput, setRakeInput] = useState('');
  const [expandedIds, setExpandedIds] = useState(new Set());
```

- [ ] **Step 3: Reset `expandedIds` in `fetchStats`, and add toggle helpers**

Find:

```jsx
  const fetchStats = (agentId, from, to) => {
    setStatsLoading(true);
    const params = {};
    if (from) params.from = from;
    if (to) params.to = to;
    getAgentPlayerStats(agentId, params)
      .then(r => { setPlayerStats(r.data); setStatsLoading(false); })
      .catch(() => { setPlayerStats([]); setStatsLoading(false); });
  };

  const handleFilter = () => fetchStats(selected.id, filterFrom, filterTo);
  const handleClearFilter = () => { setFilterFrom(''); setFilterTo(''); fetchStats(selected.id, '', ''); };
```

Replace with:

```jsx
  const fetchStats = (agentId, from, to) => {
    setStatsLoading(true);
    setExpandedIds(new Set());
    const params = {};
    if (from) params.from = from;
    if (to) params.to = to;
    getAgentPlayerStats(agentId, params)
      .then(r => { setPlayerStats(r.data); setStatsLoading(false); })
      .catch(() => { setPlayerStats([]); setStatsLoading(false); });
  };

  const handleFilter = () => fetchStats(selected.id, filterFrom, filterTo);
  const handleClearFilter = () => { setFilterFrom(''); setFilterTo(''); fetchStats(selected.id, '', ''); };

  const toggleExpand = (playerId) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(playerId)) next.delete(playerId); else next.add(playerId);
      return next;
    });
  };

  const expandAll = () => setExpandedIds(new Set(playerStats.map(p => p.playerId)));
  const collapseAll = () => setExpandedIds(new Set());
```

- [ ] **Step 4: Add `statsTotalPnl`**

Find:

```jsx
  // Player stats totals
  const statsTotalRake = playerStats.reduce((s, p) => s + Number(p.totalRake || 0), 0);
  const statsTotalShare = playerStats.reduce((s, p) => s + Number(p.agentShare || 0), 0);
```

Replace with:

```jsx
  // Player stats totals
  const statsTotalRake = playerStats.reduce((s, p) => s + Number(p.totalRake || 0), 0);
  const statsTotalShare = playerStats.reduce((s, p) => s + Number(p.agentShare || 0), 0);
  const statsTotalPnl = playerStats.reduce((s, p) => s + Number(p.periodPnl || 0), 0);
```

- [ ] **Step 5: Add Expand All / Collapse All buttons next to the "Players (N)" label**

Find:

```jsx
          <div className="card" style={{ marginBottom: '1.5rem' }}>
            <div style={{ marginBottom: '1rem' }}>
              <strong style={{ color: '#e2e8f0' }}>Players ({playerStats.length})</strong>
              {(filterFrom || filterTo) && <span style={{ color: '#64748b', fontSize: '0.8rem', marginLeft: '0.75rem' }}>{filterFrom || '…'} – {filterTo || '…'}</span>}
            </div>
```

Replace with:

```jsx
          <div className="card" style={{ marginBottom: '1.5rem' }}>
            <div style={{ marginBottom: '1rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '0.5rem' }}>
              <div>
                <strong style={{ color: '#e2e8f0' }}>Players ({playerStats.length})</strong>
                {(filterFrom || filterTo) && <span style={{ color: '#64748b', fontSize: '0.8rem', marginLeft: '0.75rem' }}>{filterFrom || '…'} – {filterTo || '…'}</span>}
              </div>
              {playerStats.length > 0 && (
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button onClick={expandAll} style={{ padding: '4px 10px', borderRadius: '5px', border: '1px solid #2d3148', background: 'transparent', color: '#94a3b8', cursor: 'pointer', fontSize: '0.78rem' }}>Expand All</button>
                  <button onClick={collapseAll} style={{ padding: '4px 10px', borderRadius: '5px', border: '1px solid #2d3148', background: 'transparent', color: '#94a3b8', cursor: 'pointer', fontSize: '0.78rem' }}>Collapse All</button>
                </div>
              )}
            </div>
```

- [ ] **Step 6: Add the "Period P&L" column header**

Find:

```jsx
                      <th style={{ padding: '8px', textAlign: 'right' }}>Agent Share</th>
                    </tr>
```

Replace with:

```jsx
                      <th style={{ padding: '8px', textAlign: 'right' }}>Agent Share</th>
                      <th style={{ padding: '8px', textAlign: 'right' }}>Period P&L</th>
                    </tr>
```

- [ ] **Step 7: Replace the row map with `AgentPlayerRow`, update colSpan, and add the Period P&L total**

Find:

```jsx
                  <tbody>
                    {playerStats.map(p => (
                      <tr key={p.playerId} style={{ borderBottom: '1px solid #1e2235' }}>
                        <td style={{ padding: '8px' }}>
                          <Link to={`/player/${p.playerId}`} style={{ color: '#60a5fa', textDecoration: 'underline' }}>
                            {p.username}
                          </Link>
                          {p.fullName && <span style={{ color: '#64748b', fontSize: '0.8rem', marginLeft: '0.4rem' }}>{p.fullName}</span>}
                        </td>
                        <td style={{ padding: '8px', textAlign: 'right', color: Number(p.balance) < 0 ? '#f87171' : '#4ade80', fontWeight: 600 }}>{fmt(p.balance)}</td>
                        <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{p.gameCount}</td>
                        <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{fmt(p.totalRake)}</td>
                        <td style={{ padding: '8px', textAlign: 'right', color: '#fbbf24', fontWeight: 600 }}>{fmt(p.agentShare)}</td>
                      </tr>
                    ))}
                    {playerStats.length === 0 && (
                      <tr><td colSpan={5} style={{ padding: '1rem', color: '#64748b', textAlign: 'center' }}>No data for selected period</td></tr>
                    )}
                    {playerStats.length > 0 && (
                      <tr style={{ borderTop: '1px solid #334155', background: '#12151f' }}>
                        <td style={{ padding: '8px', color: '#e2e8f0', fontWeight: 700 }}>Total</td>
                        <td />
                        <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{playerStats.reduce((s, p) => s + p.gameCount, 0)}</td>
                        <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8', fontWeight: 600 }}>{fmt(statsTotalRake)}</td>
                        <td style={{ padding: '8px', textAlign: 'right', color: '#fbbf24', fontWeight: 700 }}>{fmt(statsTotalShare)}</td>
                      </tr>
                    )}
                  </tbody>
```

Replace with:

```jsx
                  <tbody>
                    {playerStats.map(p => (
                      <AgentPlayerRow key={p.playerId} player={p} showBalance={true} expanded={expandedIds.has(p.playerId)} onToggle={() => toggleExpand(p.playerId)} />
                    ))}
                    {playerStats.length === 0 && (
                      <tr><td colSpan={6} style={{ padding: '1rem', color: '#64748b', textAlign: 'center' }}>No data for selected period</td></tr>
                    )}
                    {playerStats.length > 0 && (
                      <tr style={{ borderTop: '1px solid #334155', background: '#12151f' }}>
                        <td style={{ padding: '8px', color: '#e2e8f0', fontWeight: 700 }}>Total</td>
                        <td />
                        <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8' }}>{playerStats.reduce((s, p) => s + p.gameCount, 0)}</td>
                        <td style={{ padding: '8px', textAlign: 'right', color: '#94a3b8', fontWeight: 600 }}>{fmt(statsTotalRake)}</td>
                        <td style={{ padding: '8px', textAlign: 'right', color: '#fbbf24', fontWeight: 700 }}>{fmt(statsTotalShare)}</td>
                        <td style={{ padding: '8px', textAlign: 'right', fontWeight: 700 }} className={balanceClass(statsTotalPnl)}>{fmt(statsTotalPnl)}</td>
                      </tr>
                    )}
                  </tbody>
```

Note: `Link` (imported on line 2: `import { useNavigate, Link } from 'react-router-dom';`) is no longer used directly in `Agents.jsx` after this edit (it's only used inside `AgentPlayerRow` now). Remove it:

Find:

```jsx
import { useNavigate, Link } from 'react-router-dom';
```

Replace with:

```jsx
import { useNavigate } from 'react-router-dom';
```

- [ ] **Step 8: Lint check**

```bash
rtk npx eslint src/pages/Agents.jsx
```

(Run from `c:/projects/poker-frontend`.) This file has 3 pre-existing errors unrelated to this change (`'navigate' is assigned a value but never used`, a `react-hooks/set-state-in-effect` error on the `useEffect`, and `'handleFilter' is assigned a value but never used`) — out of scope for this plan, do not fix them. Expected: the same 3 errors as before this task's edits, and nothing new referencing `AgentPlayerRow`, `balanceClass`, `Link`, or `colSpan`.

- [ ] **Step 9: Commit**

```bash
rtk git add src/pages/Agents.jsx
rtk git commit -m "Add Period P&L column and game drill-down to Agents admin players table"
```

(Run from `c:/projects/poker-frontend`.)

---

## Task 6: End-to-end browser verification (closes spec Section 3)

**Files:** None.

- [ ] **Step 1: Start the frontend dev server**

```bash
rtk npm run dev
```

(Run from `c:/projects/poker-frontend`, in the background.) `.env.local` already sets `VITE_API_URL=http://localhost:8080/api`, so the dev server talks to the local backend. Wait for the "Local: http://localhost:5173/" message.

- [ ] **Step 2: Open the app and inject an admin session**

Open `http://localhost:5173` in a browser, open DevTools console, and run:

```js
localStorage.setItem('auth', JSON.stringify({
  token: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhZG1pbiIsInJvbGUiOiJBRE1JTiIsImlzQWdlbnQiOmZhbHNlfQ.7HA2hmq5iHGo8LS_khRen2Ha5plILhR0mIAHVzeFthU",
  username: "admin",
  role: "ADMIN",
  isAgent: false
}));
location.reload();
```

(This is the admin JWT already minted at `/c/temp/admin_jwt.txt`, signed with the hardcoded local dev secret in `JwtUtil.java`, no expiry.) After reload, navigate to `/agents`.

- [ ] **Step 3: Verify the Agents admin page — no filter**

Click "maxsimus7" in the Agents table to open its Detail panel. Confirm:
- The Players table now has a **Period P&L** column (6 columns total: Player, Balance, Games, Club Rake, Agent Share, Period P&L), header row matches Task 5 Step 6.
- Row for "gooshpanka" shows `gameCount = 32` and some Period P&L value, colored green (positive) or red (negative) per `balanceClass`.
- "Expand All" / "Collapse All" buttons are visible above the table.

- [ ] **Step 4: Verify the date filter changes the numbers (spec Section 3)**

Set From = `15/04/2026`, To = `21/04/2026` using the date pickers. Confirm:
- "gooshpanka" row's `Games` count changes from `32` to `5`.
- Period P&L changes to **-₪3,217.00**, shown in red (negative class).
- The Total row's Period P&L also shows **-₪3,217.00** (only one player in this agent's group).

This is the live confirmation that the date filter works end-to-end and that Period P&L makes its effect visible — resolving spec Section 3 without any filter code changes.

- [ ] **Step 5: Verify row expand / drill-down**

Click the "gooshpanka" row (still filtered to 15/04–21/04). Confirm:
- A sub-table appears beneath the row with columns Date, Game, Buy-in, Cashout, Rake, P&L.
- It has exactly 5 rows, sorted by date descending (most recent first).
- The P&L column's 5 values sum to **-₪3,217.00**, matching the row's Period P&L.

Click "Collapse All", confirm the sub-table disappears. Click "Expand All", confirm it reappears.

- [ ] **Step 6: Verify AgentPortal.jsx renders correctly**

`AgentPortal.jsx` uses the same `AgentPlayerRow` component (with `showBalance={false}`) against the same `/api/agents/{id}/player-stats` endpoint already verified in Steps 3-5, so its correctness follows from the same component + data. If you want to see it directly: log in as `maxsimus7` (the agent's own account, player id 270) and open the Agent Portal page — confirm the Players table shows 5 columns (Player, Games, Club Rake, Your Share, Period P&L, no Balance column) with the same Expand All/Collapse All and drill-down behavior.

- [ ] **Step 7 (optional cleanup): revert the Task 1 test-data change**

If you don't want player 1 permanently assigned to agent 270 in the local DB:

```bash
rtk "/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d poker_tracker -c "UPDATE players SET agent_id = NULL WHERE id = 1;"
```

Expected: `UPDATE 1`. This is local-only and has no effect on production; safe to skip if you'd like to keep using this assignment for further manual testing.
