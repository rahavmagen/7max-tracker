# Show Super-Agent Name in Game Results Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show each player's super-agent name under their name on the game-results page, plus a checkbox to filter that table to only players who have one, computed efficiently (single query, no N+1) so it works for every game.

**Architecture:** Reuse the existing `AgentService.resolveTopAgent` chain-walk algorithm (already used for agent settlement) via a new public method that resolves it for every player in one pass. Wire that into the existing `/api/reports/sessions/{id}/results` endpoint. Add the display line and filter checkbox to the existing `GameResults.jsx` page. This plan spans two repos: backend at `c:/projects/tracker`, frontend at `c:/projects/poker-frontend`.

**Tech Stack:** Spring Boot (Java) backend with JUnit 5 + Mockito for unit tests; React (Vite) frontend, no new dependencies on either side.

---

## File Structure

- **Create:** `c:/projects/tracker/src/test/java/com/sevenmax/tracker/service/AgentServiceTest.java` — unit tests for the new resolution method.
- **Modify:** `c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/AgentService.java` — add `resolveSuperAgentNames()`.
- **Modify:** `c:/projects/tracker/src/main/java/com/sevenmax/tracker/controller/ReportController.java` — wire the new map into `getSessionResults`.
- **Modify:** `c:/projects/poker-frontend/src/pages/GameResults.jsx` — display line + filter checkbox.

---

### Task 1: `AgentService.resolveSuperAgentNames()` (backend, TDD)

**Files:**
- Create: `c:/projects/tracker/src/test/java/com/sevenmax/tracker/service/AgentServiceTest.java`
- Modify: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/AgentService.java`

- [ ] **Step 1: Write the failing test**

Create `c:/projects/tracker/src/test/java/com/sevenmax/tracker/service/AgentServiceTest.java`:

```java
package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock PlayerRepository playerRepository;

    AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentService(
            playerRepository, null, null, null, null, null, null, null
        );
    }

    private Player player(Long id, Boolean isAgent, Long agentId, String username) {
        Player p = new Player();
        p.setId(id);
        p.setIsAgent(isAgent);
        p.setAgentId(agentId);
        p.setUsername(username);
        return p;
    }

    @Test
    void resolveSuperAgentNames_regularPlayerUnderTopLevelAgent_mapsToThatAgent() {
        Player agent = player(1L, true, null, "TopAgent");
        Player p = player(2L, false, 1L, "regularPlayer");
        when(playerRepository.findAll()).thenReturn(List.of(agent, p));

        Map<Long, String> result = agentService.resolveSuperAgentNames();

        assertThat(result.get(2L)).isEqualTo("TopAgent");
    }

    @Test
    void resolveSuperAgentNames_playerUnderSubAgent_rollsUpToSuperAgent() {
        Player superAgent = player(1L, true, null, "SuperAgent");
        Player subAgent = player(2L, true, 1L, "SubAgent");
        Player p = player(3L, false, 2L, "regularPlayer");
        when(playerRepository.findAll()).thenReturn(List.of(superAgent, subAgent, p));

        Map<Long, String> result = agentService.resolveSuperAgentNames();

        assertThat(result.get(3L)).isEqualTo("SuperAgent");
        assertThat(result.get(2L)).isEqualTo("SuperAgent");
    }

    @Test
    void resolveSuperAgentNames_topLevelAgentThemselves_mapsToNull() {
        Player agent = player(1L, true, null, "TopAgent");
        when(playerRepository.findAll()).thenReturn(List.of(agent));

        Map<Long, String> result = agentService.resolveSuperAgentNames();

        assertThat(result.get(1L)).isNull();
    }

    @Test
    void resolveSuperAgentNames_playerWithNoAgent_mapsToNull() {
        Player p = player(1L, false, null, "regularPlayer");
        when(playerRepository.findAll()).thenReturn(List.of(p));

        Map<Long, String> result = agentService.resolveSuperAgentNames();

        assertThat(result.get(1L)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd C:/projects/tracker && ./mvnw.cmd -q -Dtest=AgentServiceTest test`
Expected: FAIL — compile error, `resolveSuperAgentNames` does not exist on `AgentService`.

- [ ] **Step 3: Write minimal implementation**

In `c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/AgentService.java`, find:

```java
    /** The top-level (super) agent a player rolls up to: walk up while the parent is itself an agent. */
    private Player resolveTopAgent(Player p, Map<Long, Player> byId) {
        Player cur = p;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (cur != null && seen.add(cur.getId())) {
            Long parentId = cur.getAgentId();
            Player parent = parentId != null ? byId.get(parentId) : null;
            if (parent != null && Boolean.TRUE.equals(parent.getIsAgent())) cur = parent;
            else break;
        }
        return cur;
    }
```

Replace with (adds the new public method right after it, same file, same section):

```java
    /** The top-level (super) agent a player rolls up to: walk up while the parent is itself an agent. */
    private Player resolveTopAgent(Player p, Map<Long, Player> byId) {
        Player cur = p;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (cur != null && seen.add(cur.getId())) {
            Long parentId = cur.getAgentId();
            Player parent = parentId != null ? byId.get(parentId) : null;
            if (parent != null && Boolean.TRUE.equals(parent.getIsAgent())) cur = parent;
            else break;
        }
        return cur;
    }

    /** Every player id mapped to their super-agent's username, or null if they have none
     *  (including a top-level agent who has nobody above them). Loads the player list once
     *  so this is safe to call for a whole result set without N+1 queries. */
    public Map<Long, String> resolveSuperAgentNames() {
        List<Player> all = playerRepository.findAll();
        Map<Long, Player> byId = all.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        Map<Long, String> result = new HashMap<>();
        for (Player p : all) {
            Player top = resolveTopAgent(p, byId);
            result.put(p.getId(), (top != null && !top.getId().equals(p.getId())) ? top.getUsername() : null);
        }
        return result;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd C:/projects/tracker && ./mvnw.cmd -q -Dtest=AgentServiceTest test`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Commit**

```bash
cd C:/projects/tracker
git add src/main/java/com/sevenmax/tracker/service/AgentService.java src/test/java/com/sevenmax/tracker/service/AgentServiceTest.java
git commit -m "Add AgentService.resolveSuperAgentNames for efficient super-agent lookup"
```

---

### Task 2: Wire into `ReportController.getSessionResults`

**Files:**
- Modify: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/controller/ReportController.java`

- [ ] **Step 1: Compute the map once and add it to each result row**

Find:

```java
    @GetMapping("/sessions/{id}/results")
    public ResponseEntity<List<Map<String, Object>>> getSessionResults(@PathVariable Long id) {
        List<Map<String, Object>> result = new ArrayList<>();
        gameResultRepository.findBySessionId(id).stream()
            .sorted((a, b) -> {
                if (a.getTournamentPlace() != null && b.getTournamentPlace() != null)
                    return Integer.compare(a.getTournamentPlace(), b.getTournamentPlace());
                if (a.getTournamentPlace() != null) return -1;
                if (b.getTournamentPlace() != null) return 1;
                return 0;
            })
            .forEach(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("playerId", r.getPlayer().getId());
                m.put("username", r.getPlayer().getUsername());
                m.put("fullName", r.getPlayer().getFullName());
                m.put("buyIn", r.getBuyIn());
                m.put("cashout", r.getCashout());
                m.put("rakePaid", r.getRakePaid());
                m.put("resultAmount", r.getResultAmount());
                m.put("tournamentPlace", r.getTournamentPlace());
                result.add(m);
            });
        return ResponseEntity.ok(result);
    }
```

Replace with:

```java
    @GetMapping("/sessions/{id}/results")
    public ResponseEntity<List<Map<String, Object>>> getSessionResults(@PathVariable Long id) {
        Map<Long, String> superAgentNames = agentService.resolveSuperAgentNames();
        List<Map<String, Object>> result = new ArrayList<>();
        gameResultRepository.findBySessionId(id).stream()
            .sorted((a, b) -> {
                if (a.getTournamentPlace() != null && b.getTournamentPlace() != null)
                    return Integer.compare(a.getTournamentPlace(), b.getTournamentPlace());
                if (a.getTournamentPlace() != null) return -1;
                if (b.getTournamentPlace() != null) return 1;
                return 0;
            })
            .forEach(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("playerId", r.getPlayer().getId());
                m.put("username", r.getPlayer().getUsername());
                m.put("fullName", r.getPlayer().getFullName());
                m.put("buyIn", r.getBuyIn());
                m.put("cashout", r.getCashout());
                m.put("rakePaid", r.getRakePaid());
                m.put("resultAmount", r.getResultAmount());
                m.put("tournamentPlace", r.getTournamentPlace());
                m.put("superAgentName", superAgentNames.get(r.getPlayer().getId()));
                result.add(m);
            });
        return ResponseEntity.ok(result);
    }
```

Note: `agentService` is already an injected field on this controller (`private final com.sevenmax.tracker.service.AgentService agentService;`) — no new field or import needed.

- [ ] **Step 2: Compile and verify no test regressions**

Run: `cd C:/projects/tracker && ./mvnw.cmd -q -DskipTests compile`
Expected: succeeds with no errors.

Run: `cd C:/projects/tracker && ./mvnw.cmd -q -Dtest=AgentServiceTest test`
Expected: still PASS (unaffected by this change, just confirming nothing broke).

- [ ] **Step 3: Commit**

```bash
cd C:/projects/tracker
git add src/main/java/com/sevenmax/tracker/controller/ReportController.java
git commit -m "Include superAgentName in session results endpoint"
```

---

### Task 3: Frontend — display line + filter checkbox

**Files:**
- Modify: `c:/projects/poker-frontend/src/pages/GameResults.jsx`

- [ ] **Step 1: Add filter state**

Find:

```js
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(true);
```

Replace with:

```js
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(true);
  const [agentOnly, setAgentOnly] = useState(false);
```

- [ ] **Step 2: Derive the filtered list**

Find:

```js
  const cls = (n) => Number(n) > 0 ? 'positive' : Number(n) < 0 ? 'negative' : 'zero';

  const fmtDate = fmtDateTime;
```

Replace with:

```js
  const cls = (n) => Number(n) > 0 ? 'positive' : Number(n) < 0 ? 'negative' : 'zero';

  const fmtDate = fmtDateTime;

  const displayedResults = agentOnly ? results.filter(r => r.superAgentName) : results;
```

- [ ] **Step 3: Add the checkbox above the table and use the filtered list**

Find:

```jsx
      {results.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '2rem', color: '#64748b' }}>
          No results found for this game.
        </div>
      ) : (
        <div className="card">
          <div className="table-wrap"><table>
            <thead>
              <tr>
                <th>#</th>
                <th>Player</th>
                <th>Buy-in</th>
                <th>Winnings</th>
                <th>Prize</th>
                {isAdmin && <th>Rake</th>}
              </tr>
            </thead>
            <tbody>
              {results.map((r, i) => (
```

Replace with:

```jsx
      {results.length > 0 && (
        <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', color: '#94a3b8', fontSize: '0.9rem', marginBottom: '0.75rem' }}>
          <input
            type="checkbox"
            checked={agentOnly}
            onChange={e => setAgentOnly(e.target.checked)}
            style={{ width: '16px', height: '16px', cursor: 'pointer' }}
          />
          Show only agent players
        </label>
      )}

      {results.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '2rem', color: '#64748b' }}>
          No results found for this game.
        </div>
      ) : displayedResults.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '2rem', color: '#64748b' }}>
          No agent players found for this game.
        </div>
      ) : (
        <div className="card">
          <div className="table-wrap"><table>
            <thead>
              <tr>
                <th>#</th>
                <th>Player</th>
                <th>Buy-in</th>
                <th>Winnings</th>
                <th>Prize</th>
                {isAdmin && <th>Rake</th>}
              </tr>
            </thead>
            <tbody>
              {displayedResults.map((r, i) => (
```

- [ ] **Step 4: Add the super-agent line under the player name**

Find:

```jsx
                  <td style={{ cursor: r.playerId ? 'pointer' : 'default' }} onClick={() => r.playerId && navigate(`/player/${r.playerId}`)}>
                    <div><strong style={{ color: '#a5b4fc' }}>{r.fullName || r.username}</strong></div>
                    {r.fullName && r.fullName !== r.username && (
                      <div style={{ color: '#64748b', fontSize: '0.8rem' }}>{r.username}</div>
                    )}
                  </td>
```

Replace with:

```jsx
                  <td style={{ cursor: r.playerId ? 'pointer' : 'default' }} onClick={() => r.playerId && navigate(`/player/${r.playerId}`)}>
                    <div><strong style={{ color: '#a5b4fc' }}>{r.fullName || r.username}</strong></div>
                    {r.fullName && r.fullName !== r.username && (
                      <div style={{ color: '#64748b', fontSize: '0.8rem' }}>{r.username}</div>
                    )}
                    {r.superAgentName && (
                      <div style={{ color: '#34d399', fontSize: '0.75rem' }}>{r.superAgentName}</div>
                    )}
                  </td>
```

- [ ] **Step 5: Verify build and lint**

Run: `cd C:/projects/poker-frontend && npx eslint src/pages/GameResults.jsx && npm run build`
Expected: no errors, build succeeds.

- [ ] **Step 6: Commit**

```bash
cd C:/projects/poker-frontend
git add src/pages/GameResults.jsx
git commit -m "Show super-agent name and add agent-only filter on game results"
```

---

### Task 4: Full regression pass

**Files:** none (verification only)

- [ ] **Step 1: Run the backend test suite**

Run: `cd C:/projects/tracker && ./mvnw.cmd -q test`
Expected: all tests pass, including the 4 new `AgentServiceTest` cases.

- [ ] **Step 2: Run the frontend test suite and lint**

Run: `cd C:/projects/poker-frontend && npx vitest run && npm run lint`
Expected: all existing tests still pass; no new lint errors in `GameResults.jsx`.

- [ ] **Step 3: Manual end-to-end check**

Start the local backend (however it's normally run — e.g. the existing `restart-backend.bat`, or `./mvnw.cmd spring-boot:run` from `c:/projects/tracker`) and the frontend dev server (`npm run dev` from `c:/projects/poker-frontend`, which already points at `http://localhost:8080/api` per `.env.local`). Log in as admin, open the Games page, click into a game session that has at least one player under an agent, and confirm:
- Players with a super agent show the agent's name in small green text under their name.
- Players with no agent show nothing extra.
- Checking "Show only agent players" narrows the table to just those rows; unchecking restores the full list.
- No console errors.

Stop both servers once confirmed.

- [ ] **Step 4: Deploy**

Per project policy, ask the user for confirmation before pushing/deploying each repo. Once confirmed:

```bash
cd C:/projects/tracker
git push
```

Backend auto-deploys on push to Railway — no extra step.

```bash
cd C:/projects/poker-frontend
git push
npx vercel --prod --scope rahavmagens-projects
```

Confirm the Vercel deploy output shows `"readyState": "READY"` and `"target": "production"`.
