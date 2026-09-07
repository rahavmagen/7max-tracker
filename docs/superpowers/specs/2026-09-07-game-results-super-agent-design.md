# Show super-agent name in game results

## Problem

On a game's results page (`GameResults.jsx`, reached by clicking a game from the Games list), the admin currently sees each player's name but has no visibility into which (super) agent that player rolls up to. Cross-checking this against the existing Dashboard "Agent" column or the Agents page is slow. This spans both repos: `c:/projects/tracker` (backend) and `c:/projects/poker-frontend` (frontend).

## Background: "super agent" concept

Already defined in `AgentService.java` (`c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/AgentService.java`): an agent can itself be under another agent (`Player.agentId` points at another agent). The topmost agent in that chain is the "super agent" — the club settles directly with them, and every sub-agent's players/own-play rolls up into their book. `resolveTopAgent(Player p, Map<Long, Player> byId)` (private, lines 114-125) already implements the walk-up-the-chain algorithm; this feature reuses it rather than reimplementing.

## Performance approach

Naively resolving each row's super agent via JPA-lazy `r.getPlayer().getAgent()...` chains would N+1 the database per result row (and per hop up the chain). Instead: load the full player list once (`playerRepository.findAll()`, same pattern `resolveTopAgent`'s callers already use), build an in-memory `Map<Long, Player>`, and resolve every player's super-agent name from that map — O(n) total per request regardless of session size, no additional queries per row. This is fast enough to apply to every game, not just specific ones.

## Changes

### 1. Backend: `AgentService.resolveSuperAgentNames()`

New public method on `AgentService`:

```java
/** Every player id mapped to their super-agent's username, or null if they have none
 *  (including a top-level agent who has nobody above them). */
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

Called once per request, not once per row.

### 2. Backend: `ReportController.getSessionResults`

`c:/projects/tracker/src/main/java/com/sevenmax/tracker/controller/ReportController.java` (method at line ~215-239). Call `agentService.resolveSuperAgentNames()` once at the top of the method, then add `m.put("superAgentName", superAgentNames.get(r.getPlayer().getId()));` to each result row's map. `ReportController` already has `AgentService agentService` injected (existing field).

### 3. Frontend: `GameResults.jsx`

`c:/projects/poker-frontend/src/pages/GameResults.jsx`. Two changes:

- Under the existing player-name cell (which already shows `fullName` on top and `username` below when they differ), add a third line — only rendered when `r.superAgentName` is truthy — showing the super-agent's name in a small green font (`color: '#34d399'`, `fontSize: '0.75rem'`), matching the green already used for the "Agent" column on the Dashboard.
- Add a checkbox above the results table, unchecked by default, labeled to filter the table to only rows where `superAgentName` is set. Follows the same checkbox styling already used elsewhere in this app (16x16px, `cursor: pointer`, label text at `0.9rem`/`#94a3b8`).

## Out of scope

- No change to `AgentService.resolveTopAgent` itself (reused as-is).
- No change to the Games list page (`Games.jsx`) — this only affects the per-game results page.
- No persistence of the checkbox state across page loads/navigation.
