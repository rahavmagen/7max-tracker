# Agent System Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add agent tracking so agents earn a percentage of rake from their players, with admin settlement and an agent self-service portal.

**Architecture:** Store `agentRakeShare` on each `GameResult` at upload time (calculated once, never recalculated). A new `AgentSettlement` entity groups settled game results into a payment, linked to an `AdminExpense` of type `AGENT`. The frontend adds an admin Agents page and an Agent Portal page for logged-in agents.

**Tech Stack:** Spring Boot / JPA (backend), React / Vite (frontend), PostgreSQL

---

## File Map

### Backend — `c:/projects/tracker`

| Action | File |
|---|---|
| Create | `src/main/java/com/sevenmax/tracker/entity/AgentSettlement.java` |
| Create | `src/main/java/com/sevenmax/tracker/repository/AgentSettlementRepository.java` |
| Create | `src/main/java/com/sevenmax/tracker/service/AgentService.java` |
| Create | `src/main/java/com/sevenmax/tracker/controller/AgentController.java` |
| Modify | `src/main/java/com/sevenmax/tracker/entity/Player.java` |
| Modify | `src/main/java/com/sevenmax/tracker/entity/GameResult.java` |
| Modify | `src/main/java/com/sevenmax/tracker/entity/AdminExpense.java` |
| Modify | `src/main/java/com/sevenmax/tracker/service/ReportService.java` |

### Frontend — `c:/projects/poker-frontend`

| Action | File |
|---|---|
| Create | `src/pages/Agents.jsx` |
| Create | `src/pages/AgentPortal.jsx` |
| Modify | `src/pages/PlayerDetail.jsx` |
| Modify | `src/pages/AdminExpenses.jsx` |
| Modify | `src/App.jsx` |
| Modify | `src/api.js` |

---

## Task 1: Extend entity fields (no DB migration needed — JPA auto-creates)

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/entity/Player.java`
- Modify: `src/main/java/com/sevenmax/tracker/entity/GameResult.java`
- Modify: `src/main/java/com/sevenmax/tracker/entity/AdminExpense.java`

- [ ] **Step 1: Add agent fields to `Player.java`**

Replace the closing brace of `Player` class (after `createdAt` field) with:

```java
    // Agent system
    private Boolean isAgent = false;

    @Column(precision = 6, scale = 4)
    private BigDecimal agentRakePercentage; // e.g. 0.3000 = 30%

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Player agent; // self-referential FK, null if no agent
}
```

Also add import at top if not already present:
```java
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
```

- [ ] **Step 2: Add agent fields to `GameResult.java`**

Replace the closing brace of `GameResult` class (after `tournamentPlace` field) with:

```java
    @Column(precision = 12, scale = 2)
    private BigDecimal agentRakeShare; // null if no agent, set once at upload, never recalculated

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_settlement_id")
    private AgentSettlement agentSettlement; // null until settled
}
```

Also add import:
```java
import com.sevenmax.tracker.entity.AgentSettlement;
```

- [ ] **Step 3: Add expense type fields to `AdminExpense.java`**

Replace the closing brace of `AdminExpense` class (after `paidFromBankAccountId` field) with:

```java
    // Agent system
    @Column(columnDefinition = "varchar(20) default 'ADMIN'")
    private String expenseType = "ADMIN"; // 'ADMIN' or 'AGENT'

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_settlement_id")
    private AgentSettlement agentSettlement; // set when this expense was created by agent settlement
}
```

Also add import:
```java
import jakarta.persistence.OneToOne;
```

- [ ] **Step 4: Compile to verify no errors**

```bash
cd c:/projects/tracker && rtk ./mvnw compile -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
cd c:/projects/tracker && rtk git add src/main/java/com/sevenmax/tracker/entity/ && rtk git commit -m "feat(agent): add agent fields to Player, GameResult, AdminExpense entities"
```

---

## Task 2: Create `AgentSettlement` entity and repository

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/entity/AgentSettlement.java`
- Create: `src/main/java/com/sevenmax/tracker/repository/AgentSettlementRepository.java`

- [ ] **Step 1: Create `AgentSettlement.java`**

```java
package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_settlements")
@Data
public class AgentSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Player agent;

    private LocalDate fromDate;
    private LocalDate toDate;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalRake = BigDecimal.ZERO; // sum of rakePaid from covered game_results

    @Column(precision = 12, scale = 2)
    private BigDecimal agentShare = BigDecimal.ZERO; // sum of agentRakeShare — actual payment amount

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_expense_id")
    private AdminExpense adminExpense;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

- [ ] **Step 2: Create `AgentSettlementRepository.java`**

```java
package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.AgentSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentSettlementRepository extends JpaRepository<AgentSettlement, Long> {
    List<AgentSettlement> findByAgentIdOrderByCreatedAtDesc(Long agentId);
}
```

- [ ] **Step 3: Compile**

```bash
cd c:/projects/tracker && rtk ./mvnw compile -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
cd c:/projects/tracker && rtk git add src/main/java/com/sevenmax/tracker/entity/AgentSettlement.java src/main/java/com/sevenmax/tracker/repository/AgentSettlementRepository.java && rtk git commit -m "feat(agent): add AgentSettlement entity and repository"
```

---

## Task 3: Wire `agentRakeShare` calculation in `ReportService`

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/service/ReportService.java`

The two save spots in `parseRingGameDetail` (lines ~1043, ~1053) and two in `parseMttDetail` (lines ~1148, ~1160) need to set `agentRakeShare` before saving. Also, we need a new `parseClubOverview` method that reads agent assignments from columns D+E.

- [ ] **Step 1: Add helper method `applyAgentRakeShare` to `ReportService`**

Add this private method just before `parseRingGameDetail`:

```java
private void applyAgentRakeShare(GameResult result) {
    if (result.getAgentRakeShare() != null) return; // already set — never recalculate
    Player player = result.getPlayer();
    if (player == null) return;
    Player agent = player.getAgent();
    if (agent == null || agent.getAgentRakePercentage() == null) return;
    result.setAgentRakeShare(
        result.getRakePaid().multiply(agent.getAgentRakePercentage())
            .setScale(2, java.math.RoundingMode.HALF_UP)
    );
}
```

- [ ] **Step 2: Call `applyAgentRakeShare` at the 4 GameResult save spots**

In `parseRingGameDetail`, both `gameResultRepository.save(result)` calls (existing result update ~line 1043, and new result ~line 1053) — add the call just before each save:

```java
// existing result (update path):
result.setRakePaid(result.getRakePaid().add(rake));
result.setResultAmount(result.getResultAmount().add(pnl));
applyAgentRakeShare(result);          // <-- ADD THIS LINE
gameResultRepository.save(result);

// new result (create path):
result.setRakePaid(rake);
result.setResultAmount(pnl);
applyAgentRakeShare(result);          // <-- ADD THIS LINE
gameResultRepository.save(result);
resultMap.put(resultKey, result);
```

Do the same for both save spots in `parseMttDetail` (same pattern, around lines 1148 and 1160).

- [ ] **Step 3: Add `parseClubOverview` method to `ReportService`**

Add this private method after `parseCreditSheet`:

```java
private void parseClubOverview(Workbook workbook) {
    Sheet sheet = workbook.getSheet("Club Overview");
    if (sheet == null) return;

    // Player rows start at row index 3 (row 4 in Excel)
    // Col A (0): player club ID, Col B (1): player nickname
    // Col D (3): agent club ID, Col E (4): agent nickname
    for (int r = 3; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;

        String playerClubId = getCellValue(row, 0);
        String agentClubId  = getCellValue(row, 3);
        String agentNickname = getCellValue(row, 4);

        if (playerClubId == null || playerClubId.isBlank()) continue;
        if (agentClubId == null || agentClubId.isBlank()) continue;

        // Find the player
        Player player = playerRepository.findByClubPlayerIdSafe(playerClubId).stream().findFirst().orElse(null);
        if (player == null) continue;

        // Find the agent player — try by clubPlayerId first, then by username
        Player agent = playerRepository.findByClubPlayerIdSafe(agentClubId).stream().findFirst()
                .or(() -> agentNickname != null ? findPlayerByUsername(agentNickname) : java.util.Optional.empty())
                .orElse(null);

        if (agent == null || !Boolean.TRUE.equals(agent.getIsAgent())) continue;

        // Only update if different (avoid dirty writes)
        if (player.getAgent() == null || !agent.getId().equals(player.getAgent().getId())) {
            player.setAgent(agent);
            playerRepository.save(player);
            log.info("Agent assigned: player={} agent={}", player.getUsername(), agent.getUsername());
        }
    }
}
```

- [ ] **Step 4: Call `parseClubOverview` in `uploadReport`, after `parseCreditSheet`**

Locate the line `parseTradeRecord(workbook, report);` and add the call just before it:

```java
parseClubOverview(workbook);
parseTradeRecord(workbook, report);
```

- [ ] **Step 5: Compile**

```bash
cd c:/projects/tracker && rtk ./mvnw compile -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
cd c:/projects/tracker && rtk git add src/main/java/com/sevenmax/tracker/service/ReportService.java && rtk git commit -m "feat(agent): calculate agentRakeShare on upload, sync agent from Club Overview"
```

---

## Task 4: Create `AgentService`

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/service/AgentService.java`

- [ ] **Step 1: Create `AgentService.java`**

```java
package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final PlayerRepository playerRepository;
    private final GameResultRepository gameResultRepository;
    private final AgentSettlementRepository agentSettlementRepository;
    private final AdminExpenseRepository adminExpenseRepository;

    /** All agents with their pending (unsettled) balance */
    public List<Map<String, Object>> getAllAgentsSummary() {
        return playerRepository.findAll().stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsAgent()))
            .map(agent -> {
                BigDecimal pending = getUnsettledResults(agent.getId()).stream()
                    .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                long playerCount = playerRepository.findAll().stream()
                    .filter(p -> p.getAgent() != null && agent.getId().equals(p.getAgent().getId()))
                    .count();
                List<AgentSettlement> settlements = agentSettlementRepository.findByAgentIdOrderByCreatedAtDesc(agent.getId());
                LocalDate lastSettlement = settlements.isEmpty() ? null : settlements.get(0).getToDate();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", agent.getId());
                m.put("username", agent.getUsername());
                m.put("fullName", agent.getFullName());
                m.put("pendingBalance", pending);
                m.put("playerCount", playerCount);
                m.put("lastSettlementDate", lastSettlement != null ? lastSettlement.toString() : null);
                return m;
            })
            .collect(Collectors.toList());
    }

    /** Pending balance + settlement history for one agent */
    public Map<String, Object> getAgentSummary(Long agentId) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        List<GameResult> unsettled = getUnsettledResults(agentId);
        BigDecimal pending = unsettled.stream()
            .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AgentSettlement> settlements = agentSettlementRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
        List<Map<String, Object>> historyList = settlements.stream().map(s -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("id", s.getId());
            h.put("fromDate", s.getFromDate() != null ? s.getFromDate().toString() : null);
            h.put("toDate", s.getToDate() != null ? s.getToDate().toString() : null);
            h.put("totalRake", s.getTotalRake());
            h.put("agentShare", s.getAgentShare());
            h.put("status", "PAID");
            return h;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("username", agent.getUsername());
        result.put("pendingBalance", pending);
        result.put("settlementHistory", historyList);
        return result;
    }

    /** Game-by-game breakdown of unsettled results for an agent, optional date filter */
    public List<Map<String, Object>> getAgentBreakdown(Long agentId, LocalDate from, LocalDate to) {
        return getUnsettledResults(agentId).stream()
            .filter(gr -> {
                LocalDate sessionDate = gr.getSession().getStartTime().toLocalDate();
                if (from != null && sessionDate.isBefore(from)) return false;
                if (to != null && sessionDate.isAfter(to)) return false;
                return true;
            })
            .map(gr -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("gameResultId", gr.getId());
                m.put("sessionDate", gr.getSession().getStartTime().toLocalDate().toString());
                m.put("tableName", gr.getSession().getTableName());
                m.put("playerUsername", gr.getPlayer().getUsername());
                m.put("rakePaid", gr.getRakePaid());
                m.put("agentShare", gr.getAgentRakeShare());
                m.put("status", "pending");
                return m;
            })
            .collect(Collectors.toList());
    }

    /** Create a settlement: mark all unsettled results, create AgentSettlement + AdminExpense */
    @Transactional
    public AgentSettlement settleAgent(Long agentId) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        List<GameResult> unsettled = getUnsettledResults(agentId);
        if (unsettled.isEmpty()) throw new IllegalStateException("No pending balance to settle");

        BigDecimal totalRake = unsettled.stream()
            .map(GameResult::getRakePaid)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal agentShare = unsettled.stream()
            .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate fromDate = unsettled.stream()
            .map(gr -> gr.getSession().getStartTime().toLocalDate())
            .min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate toDate = unsettled.stream()
            .map(gr -> gr.getSession().getStartTime().toLocalDate())
            .max(LocalDate::compareTo).orElse(LocalDate.now());

        // Create AdminExpense of type AGENT
        AdminExpense expense = new AdminExpense();
        expense.setAdminUsername(agent.getUsername());
        expense.setAmount(agentShare);
        expense.setNotes("Agent fee: " + fromDate + " – " + toDate);
        expense.setExpenseDate(LocalDate.now());
        expense.setCreatedBy("system");
        expense.setExpenseType("AGENT");
        expense = adminExpenseRepository.save(expense);

        // Create AgentSettlement
        AgentSettlement settlement = new AgentSettlement();
        settlement.setAgent(agent);
        settlement.setFromDate(fromDate);
        settlement.setToDate(toDate);
        settlement.setTotalRake(totalRake);
        settlement.setAgentShare(agentShare);
        settlement.setAdminExpense(expense);
        settlement = agentSettlementRepository.save(settlement);

        // Link expense back to settlement
        expense.setAgentSettlement(settlement);
        adminExpenseRepository.save(expense);

        // Mark all game results as settled
        final AgentSettlement finalSettlement = settlement;
        for (GameResult gr : unsettled) {
            gr.setAgentSettlement(finalSettlement);
            gameResultRepository.save(gr);
        }

        return settlement;
    }

    private List<GameResult> getUnsettledResults(Long agentId) {
        // Find all players belonging to this agent
        List<Long> playerIds = playerRepository.findAll().stream()
            .filter(p -> p.getAgent() != null && agentId.equals(p.getAgent().getId()))
            .map(Player::getId)
            .collect(Collectors.toList());

        if (playerIds.isEmpty()) return Collections.emptyList();

        // Find game results with agentRakeShare set but not yet settled
        return gameResultRepository.findAll().stream()
            .filter(gr -> playerIds.contains(gr.getPlayer().getId()))
            .filter(gr -> gr.getAgentRakeShare() != null)
            .filter(gr -> gr.getAgentSettlement() == null)
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd c:/projects/tracker && rtk ./mvnw compile -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
cd c:/projects/tracker && rtk git add src/main/java/com/sevenmax/tracker/service/AgentService.java && rtk git commit -m "feat(agent): add AgentService with summary, breakdown, settle"
```

---

## Task 5: Create `AgentController`

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/controller/AgentController.java`

- [ ] **Step 1: Create `AgentController.java`**

```java
package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.AgentSettlement;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final UserRepository userRepository;

    /** Admin: list all agents with pending balances */
    @GetMapping
    public ResponseEntity<?> getAllAgents(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(agentService.getAllAgentsSummary());
    }

    /** Agent or admin: pending balance + settlement history */
    @GetMapping("/{id}/summary")
    public ResponseEntity<?> getSummary(@PathVariable Long id, Authentication auth) {
        if (!isAdmin(auth) && !isOwner(auth, id)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(agentService.getAgentSummary(id));
    }

    /** Agent or admin: game-by-game breakdown, optional ?from=&to= */
    @GetMapping("/{id}/breakdown")
    public ResponseEntity<?> getBreakdown(
            @PathVariable Long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication auth) {
        if (!isAdmin(auth) && !isOwner(auth, id)) return ResponseEntity.status(403).build();
        LocalDate fromDate = from != null ? LocalDate.parse(from) : null;
        LocalDate toDate   = to   != null ? LocalDate.parse(to)   : null;
        return ResponseEntity.ok(agentService.getAgentBreakdown(id, fromDate, toDate));
    }

    /** Admin only: trigger settlement */
    @PostMapping("/{id}/settle")
    public ResponseEntity<?> settle(@PathVariable Long id, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            AgentSettlement settlement = agentService.settleAgent(id);
            return ResponseEntity.ok(Map.of(
                "settlementId", settlement.getId(),
                "agentShare", settlement.getAgentShare(),
                "fromDate", settlement.getFromDate().toString(),
                "toDate", settlement.getToDate().toString()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        return user != null && (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MANAGER);
    }

    private boolean isOwner(Authentication auth, Long agentId) {
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null || user.getPlayer() == null) return false;
        return agentId.equals(user.getPlayer().getId());
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd c:/projects/tracker && rtk ./mvnw compile -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Start backend, verify endpoints exist**

```bash
cd c:/projects/tracker && rtk ./mvnw spring-boot:run &
sleep 10
curl -s http://localhost:8080/api/agents -H "Authorization: Bearer invalid" | head -5
```

Expected: 401 or 403 response (proves endpoint is registered)

Kill the background process after verifying.

- [ ] **Step 4: Commit**

```bash
cd c:/projects/tracker && rtk git add src/main/java/com/sevenmax/tracker/controller/AgentController.java && rtk git commit -m "feat(agent): add AgentController with summary/breakdown/settle endpoints"
```

---

## Task 6: Update `PlayerDetail.jsx` — admin agent fields

**Files:**
- Modify: `src/pages/PlayerDetail.jsx`

- [ ] **Step 1: Read current PlayerDetail.jsx to find the player save form**

Read `src/pages/PlayerDetail.jsx` and find where `fullName`, `phone`, `active` state is managed and where the PUT is called. Identify the form submit section.

- [ ] **Step 2: Add `isAgent`, `agentRakePercentage`, `agentId` to the edit state**

In the section where player edit state is initialized (e.g., `const [editData, setEditData] = useState(...)`), add these three fields.

When loading player data into edit state:
```jsx
isAgent: player.isAgent || false,
agentRakePercentage: player.agentRakePercentage || '',
agentId: player.agent?.id || '',
```

- [ ] **Step 3: Add agent fields to the edit form (admin only)**

In the edit form, after the existing phone/active fields, add:

```jsx
{isAdmin && (
  <>
    <div style={{ marginTop: '1rem' }}>
      <label>
        <input
          type="checkbox"
          checked={editData.isAgent || false}
          onChange={e => setEditData(d => ({ ...d, isAgent: e.target.checked }))}
        />{' '}
        This player is an agent
      </label>
    </div>
    {editData.isAgent && (
      <div style={{ marginTop: '0.5rem' }}>
        <label>Rake % (0–100):</label>
        <input
          type="number"
          min="0"
          max="100"
          step="1"
          value={editData.agentRakePercentage !== '' ? Math.round(Number(editData.agentRakePercentage) * 100) : ''}
          onChange={e => setEditData(d => ({ ...d, agentRakePercentage: e.target.value !== '' ? (Number(e.target.value) / 100).toFixed(4) : '' }))}
          style={{ width: '80px' }}
        />
        <span>%</span>
      </div>
    )}
    <div style={{ marginTop: '0.5rem' }}>
      <label>Assigned agent:</label>
      <select
        value={editData.agentId || ''}
        onChange={e => setEditData(d => ({ ...d, agentId: e.target.value }))}
      >
        <option value="">None</option>
        {agents.map(a => (
          <option key={a.id} value={a.id}>{a.username}</option>
        ))}
      </select>
    </div>
  </>
)}
```

- [ ] **Step 4: Load agents list**

Add state and fetch for agents list (list of players with `isAgent=true`):

```jsx
const [agents, setAgents] = useState([]);

useEffect(() => {
  if (isAdmin) {
    getAgents().then(r => setAgents(r.data));
  }
}, [isAdmin]);
```

- [ ] **Step 5: Include agent fields in the PUT payload**

When building the update payload, include:
```jsx
isAgent: editData.isAgent,
agentRakePercentage: editData.agentRakePercentage !== '' ? editData.agentRakePercentage : null,
agentId: editData.agentId || null,
```

The backend `PlayerService.updatePlayer` needs to accept these — see Task 7.

- [ ] **Step 6: Commit**

```bash
cd c:/projects/poker-frontend && rtk git add src/pages/PlayerDetail.jsx && rtk git commit -m "feat(agent): add agent fields to PlayerDetail admin edit form"
```

---

## Task 7: Update `PlayerService.updatePlayer` to accept agent fields

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/service/PlayerService.java`

- [ ] **Step 1: Read `PlayerService.java`**

Read `src/main/java/com/sevenmax/tracker/service/PlayerService.java` to find `updatePlayer`.

- [ ] **Step 2: Add agent field updates to `updatePlayer`**

In `updatePlayer`, after the existing field updates (fullName, phone, etc.), add:

```java
// Agent system fields
if (updated.getIsAgent() != null) {
    player.setIsAgent(updated.getIsAgent());
}
if (updated.getAgentRakePercentage() != null) {
    player.setAgentRakePercentage(updated.getAgentRakePercentage());
}
// agentId is a separate FK — frontend passes it as player.agent.id
// The updated Player object won't have the full nested agent, so we use
// a separate field. We'll handle this via a dedicated endpoint instead.
```

> Note: The cleanest way to update agent assignment is via a separate `PATCH /api/players/{id}/agent` endpoint or by accepting `agentId` as a plain field in the update body. See the `PlayerController` approach below.

- [ ] **Step 3: Add `PATCH /api/players/{id}/agent` to `PlayerController`**

Read `src/main/java/com/sevenmax/tracker/controller/PlayerController.java` and add:

```java
@PatchMapping("/{id}/agent")
public ResponseEntity<?> setAgent(@PathVariable Long id, @RequestBody Map<String, Object> body) {
    Player player = playerService.getPlayer(id);
    Object agentIdVal = body.get("agentId");
    if (agentIdVal == null || agentIdVal.toString().isBlank()) {
        player.setAgent(null);
    } else {
        Long agentId = Long.parseLong(agentIdVal.toString());
        Player agent = playerService.getPlayer(agentId);
        player.setAgent(agent);
    }
    return ResponseEntity.ok(playerRepository.save(player));
}
```

Also add `private final PlayerRepository playerRepository;` to the controller if it's not already injected.

- [ ] **Step 4: Add player agent fields to the player JSON response**

In `PlayerController.getPlayer` (or wherever the player JSON is built), ensure `isAgent`, `agentRakePercentage`, and `agent.id` / `agent.username` are included. If the endpoint returns the JPA entity directly, Lombok's `@Data` will serialize these fields automatically. Just verify `agent` doesn't cause a circular serialization issue — if so, add `@JsonIgnoreProperties({"agent", "hibernateLazyInitializer"})` on the `agent` field in `Player.java`.

- [ ] **Step 5: Compile**

```bash
cd c:/projects/tracker && rtk ./mvnw compile -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
cd c:/projects/tracker && rtk git add src/main/java/com/sevenmax/tracker/service/PlayerService.java src/main/java/com/sevenmax/tracker/controller/PlayerController.java src/main/java/com/sevenmax/tracker/entity/Player.java && rtk git commit -m "feat(agent): update PlayerService/Controller to accept agent fields"
```

---

## Task 8: Add API functions + `Agents.jsx` admin page

**Files:**
- Modify: `src/api.js`
- Create: `src/pages/Agents.jsx`

- [ ] **Step 1: Add agent API functions to `api.js`**

Append to `src/api.js`:

```js
export const getAgents = () => api.get('/agents');
export const getAgentSummary = (id) => api.get(`/agents/${id}/summary`);
export const getAgentBreakdown = (id, params) => api.get(`/agents/${id}/breakdown`, { params });
export const settleAgent = (id) => api.post(`/agents/${id}/settle`);
export const setPlayerAgent = (playerId, agentId) => api.patch(`/players/${playerId}/agent`, { agentId });
```

- [ ] **Step 2: Create `Agents.jsx`**

```jsx
import { useState, useEffect } from 'react';
import { getAgents, getAgentBreakdown, settleAgent } from '../api';

export default function Agents() {
  const [agents, setAgents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(null); // { agentId, username }
  const [breakdown, setBreakdown] = useState([]);
  const [bdLoading, setBdLoading] = useState(false);
  const [msg, setMsg] = useState(null);
  const [settling, setSettling] = useState(false);

  const fmt = (n) => {
    if (n === undefined || n === null) return '₪0';
    const abs = Math.abs(Number(n)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return '₪' + abs;
  };

  const load = () => {
    setLoading(true);
    getAgents().then(r => { setAgents(r.data); setLoading(false); });
  };

  useEffect(() => { load(); }, []);

  const openBreakdown = (agent) => {
    setSelected(agent);
    setBdLoading(true);
    getAgentBreakdown(agent.id).then(r => { setBreakdown(r.data); setBdLoading(false); });
  };

  const handleSettle = async (agentId) => {
    setSettling(true);
    setMsg(null);
    try {
      const r = await settleAgent(agentId);
      setMsg({ type: 'success', text: `Settled ₪${r.data.agentShare} for ${r.data.fromDate} – ${r.data.toDate}` });
      load();
      if (selected?.id === agentId) openBreakdown({ id: agentId, username: selected.username });
    } catch (e) {
      setMsg({ type: 'error', text: e.response?.data?.error || 'Failed to settle' });
    }
    setSettling(false);
  };

  if (loading) return <div className="page-container">Loading...</div>;

  return (
    <div className="page-container">
      <h2>Agents</h2>
      {msg && (
        <div style={{ padding: '0.5rem 1rem', marginBottom: '1rem', borderRadius: '6px',
          background: msg.type === 'success' ? '#1a3a1a' : '#3a1a1a', color: msg.type === 'success' ? '#4ade80' : '#f87171' }}>
          {msg.text}
        </div>
      )}

      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '2rem' }}>
        <thead>
          <tr style={{ borderBottom: '1px solid #2d3148', color: '#94a3b8', textAlign: 'left', fontSize: '0.85rem' }}>
            <th style={{ padding: '8px' }}>Agent</th>
            <th style={{ padding: '8px' }}>Players</th>
            <th style={{ padding: '8px' }}>Pending Balance</th>
            <th style={{ padding: '8px' }}>Last Settlement</th>
            <th style={{ padding: '8px' }}>Action</th>
          </tr>
        </thead>
        <tbody>
          {agents.map(a => (
            <tr key={a.id} style={{ borderBottom: '1px solid #1e2235' }}>
              <td style={{ padding: '8px' }}>
                <button
                  onClick={() => openBreakdown(a)}
                  style={{ background: 'none', border: 'none', color: '#60a5fa', cursor: 'pointer', padding: 0 }}
                >
                  {a.username}
                </button>
              </td>
              <td style={{ padding: '8px', color: '#94a3b8' }}>{a.playerCount}</td>
              <td style={{ padding: '8px', color: Number(a.pendingBalance) > 0 ? '#fbbf24' : '#94a3b8' }}>
                {fmt(a.pendingBalance)}
              </td>
              <td style={{ padding: '8px', color: '#94a3b8' }}>{a.lastSettlementDate || '—'}</td>
              <td style={{ padding: '8px' }}>
                <button
                  onClick={() => handleSettle(a.id)}
                  disabled={settling || Number(a.pendingBalance) <= 0}
                  style={{
                    padding: '4px 12px', borderRadius: '6px', border: 'none', cursor: 'pointer',
                    background: Number(a.pendingBalance) > 0 ? '#1d4ed8' : '#374151',
                    color: '#fff', opacity: settling ? 0.6 : 1
                  }}
                >
                  Settle & Pay
                </button>
              </td>
            </tr>
          ))}
          {agents.length === 0 && (
            <tr><td colSpan={5} style={{ padding: '1rem', color: '#64748b', textAlign: 'center' }}>No agents configured</td></tr>
          )}
        </tbody>
      </table>

      {selected && (
        <div>
          <h3 style={{ marginBottom: '1rem' }}>{selected.username} — Game Breakdown</h3>
          {bdLoading ? <div>Loading...</div> : (
            <>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid #2d3148', color: '#94a3b8', textAlign: 'left', fontSize: '0.85rem' }}>
                    <th style={{ padding: '8px' }}>Date</th>
                    <th style={{ padding: '8px' }}>Table</th>
                    <th style={{ padding: '8px' }}>Player</th>
                    <th style={{ padding: '8px' }}>Rake</th>
                    <th style={{ padding: '8px' }}>Agent Share</th>
                    <th style={{ padding: '8px' }}>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {breakdown.map(row => (
                    <tr key={row.gameResultId} style={{ borderBottom: '1px solid #1e2235' }}>
                      <td style={{ padding: '8px', color: '#94a3b8' }}>{row.sessionDate}</td>
                      <td style={{ padding: '8px' }}>{row.tableName}</td>
                      <td style={{ padding: '8px' }}>{row.playerUsername}</td>
                      <td style={{ padding: '8px' }}>{fmt(row.rakePaid)}</td>
                      <td style={{ padding: '8px', color: '#fbbf24' }}>{fmt(row.agentShare)}</td>
                      <td style={{ padding: '8px', color: '#64748b', fontSize: '0.8rem' }}>{row.status}</td>
                    </tr>
                  ))}
                  {breakdown.length === 0 && (
                    <tr><td colSpan={6} style={{ padding: '1rem', color: '#64748b', textAlign: 'center' }}>No pending results</td></tr>
                  )}
                </tbody>
              </table>
              {breakdown.length > 0 && (
                <div style={{ marginTop: '1rem', display: 'flex', justifyContent: 'flex-end', gap: '2rem', alignItems: 'center' }}>
                  <span style={{ color: '#94a3b8' }}>
                    Total Rake: {fmt(breakdown.reduce((s, r) => s + Number(r.rakePaid), 0))}
                  </span>
                  <span style={{ color: '#fbbf24', fontWeight: 600 }}>
                    Agent Share: {fmt(breakdown.reduce((s, r) => s + Number(r.agentShare), 0))}
                  </span>
                  <button
                    onClick={() => handleSettle(selected.id)}
                    disabled={settling}
                    style={{ padding: '6px 16px', borderRadius: '6px', border: 'none', background: '#1d4ed8', color: '#fff', cursor: 'pointer' }}
                  >
                    Settle & Pay
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Commit**

```bash
cd c:/projects/poker-frontend && rtk git add src/api.js src/pages/Agents.jsx && rtk git commit -m "feat(agent): add Agents admin page and API functions"
```

---

## Task 9: Create `AgentPortal.jsx`

**Files:**
- Create: `src/pages/AgentPortal.jsx`

- [ ] **Step 1: Create `AgentPortal.jsx`**

```jsx
import { useState, useEffect } from 'react';
import { useAuth } from '../auth/AuthContext';
import { getAgentSummary, getAgentBreakdown } from '../api';

export default function AgentPortal() {
  const { auth } = useAuth();
  const agentId = auth?.playerId;
  const [summary, setSummary] = useState(null);
  const [breakdown, setBreakdown] = useState([]);
  const [loading, setLoading] = useState(true);

  const fmt = (n) => {
    if (n === undefined || n === null) return '₪0';
    const abs = Math.abs(Number(n)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return '₪' + abs;
  };

  useEffect(() => {
    if (!agentId) return;
    Promise.all([getAgentSummary(agentId), getAgentBreakdown(agentId)])
      .then(([sRes, bdRes]) => {
        setSummary(sRes.data);
        setBreakdown(bdRes.data);
        setLoading(false);
      });
  }, [agentId]);

  if (loading) return <div className="page-container">Loading...</div>;
  if (!summary) return <div className="page-container">Agent data not found</div>;

  return (
    <div className="page-container">
      <h2>Agent Portal</h2>

      <div style={{ background: '#0f172a', border: '1px solid #2d3148', borderRadius: '12px', padding: '2rem', marginBottom: '2rem', textAlign: 'center' }}>
        <div style={{ color: '#94a3b8', fontSize: '0.9rem', marginBottom: '0.5rem' }}>Pending Balance</div>
        <div style={{ fontSize: '2.5rem', fontWeight: 700, color: '#fbbf24' }}>{fmt(summary.pendingBalance)}</div>
      </div>

      <h3 style={{ marginBottom: '1rem' }}>Settlement History</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '2rem' }}>
        <thead>
          <tr style={{ borderBottom: '1px solid #2d3148', color: '#94a3b8', textAlign: 'left', fontSize: '0.85rem' }}>
            <th style={{ padding: '8px' }}>Period</th>
            <th style={{ padding: '8px' }}>Total Rake</th>
            <th style={{ padding: '8px' }}>Your Share</th>
            <th style={{ padding: '8px' }}>Status</th>
          </tr>
        </thead>
        <tbody>
          {summary.settlementHistory.map(s => (
            <tr key={s.id} style={{ borderBottom: '1px solid #1e2235' }}>
              <td style={{ padding: '8px' }}>{s.fromDate} – {s.toDate}</td>
              <td style={{ padding: '8px', color: '#94a3b8' }}>{fmt(s.totalRake)}</td>
              <td style={{ padding: '8px', color: '#4ade80' }}>{fmt(s.agentShare)}</td>
              <td style={{ padding: '8px', color: '#64748b', fontSize: '0.8rem' }}>{s.status}</td>
            </tr>
          ))}
          {summary.settlementHistory.length === 0 && (
            <tr><td colSpan={4} style={{ padding: '1rem', color: '#64748b', textAlign: 'center' }}>No settlements yet</td></tr>
          )}
        </tbody>
      </table>

      <h3 style={{ marginBottom: '1rem' }}>Pending Games</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr style={{ borderBottom: '1px solid #2d3148', color: '#94a3b8', textAlign: 'left', fontSize: '0.85rem' }}>
            <th style={{ padding: '8px' }}>Date</th>
            <th style={{ padding: '8px' }}>Table</th>
            <th style={{ padding: '8px' }}>Player</th>
            <th style={{ padding: '8px' }}>Rake</th>
            <th style={{ padding: '8px' }}>Your Share</th>
          </tr>
        </thead>
        <tbody>
          {breakdown.map(row => (
            <tr key={row.gameResultId} style={{ borderBottom: '1px solid #1e2235' }}>
              <td style={{ padding: '8px', color: '#94a3b8' }}>{row.sessionDate}</td>
              <td style={{ padding: '8px' }}>{row.tableName}</td>
              <td style={{ padding: '8px' }}>{row.playerUsername}</td>
              <td style={{ padding: '8px' }}>{fmt(row.rakePaid)}</td>
              <td style={{ padding: '8px', color: '#fbbf24' }}>{fmt(row.agentShare)}</td>
            </tr>
          ))}
          {breakdown.length === 0 && (
            <tr><td colSpan={5} style={{ padding: '1rem', color: '#64748b', textAlign: 'center' }}>No pending games</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
cd c:/projects/poker-frontend && rtk git add src/pages/AgentPortal.jsx && rtk git commit -m "feat(agent): add AgentPortal page for agent self-service"
```

---

## Task 10: Wire routes + nav in `App.jsx`

**Files:**
- Modify: `src/App.jsx`

- [ ] **Step 1: Add imports**

In `App.jsx`, after the existing page imports, add:

```jsx
import Agents from './pages/Agents';
import AgentPortal from './pages/AgentPortal';
```

- [ ] **Step 2: Add Agents route to admin section**

In the admin `<Routes>` block, after `<Route path="/admin-expenses" element={<AdminExpenses />} />`, add:

```jsx
<Route path="/agents" element={<Agents />} />
```

- [ ] **Step 3: Add AgentPortal route to player section (and agents)**

In the player `<Routes>` block, add:

```jsx
<Route path="/agent-portal" element={<AgentPortal />} />
```

Also add it to the admin routes block so admins can preview it:

```jsx
<Route path="/agent-portal" element={<AgentPortal />} />
```

- [ ] **Step 4: Add Agents nav link for admins**

In the admin `nav-links` section, after the Accounting dropdown, add:

```jsx
<NavLink to="/agents">Agents</NavLink>
```

- [ ] **Step 5: Add Agent Portal nav link for agent players**

The `auth` object comes from `/api/auth/me` which returns the player object. Add `isAgent` to the auth context check:

```jsx
const isAgent = isPlayer && auth.isAgent;
```

Then in the player nav links section, add:

```jsx
{isAgent && <NavLink to="/agent-portal">Agent Portal</NavLink>}
```

- [ ] **Step 6: Verify `auth.isAgent` is exposed by `/api/auth/me`**

The auth endpoint must return `isAgent` from the player record. Check `AuthController.java` and ensure the `/api/auth/me` response includes `player.isAgent`. If the player object is returned directly from the DB entity, Lombok `@Data` will serialize it automatically.

- [ ] **Step 7: Build frontend to verify no errors**

```bash
cd c:/projects/poker-frontend && rtk npm run build 2>&1 | tail -20
```

Expected: no errors

- [ ] **Step 8: Commit**

```bash
cd c:/projects/poker-frontend && rtk git add src/App.jsx && rtk git commit -m "feat(agent): add Agents and AgentPortal routes and nav links"
```

---

## Task 11: Add Agent Fees section to `AdminExpenses.jsx`

**Files:**
- Modify: `src/pages/AdminExpenses.jsx`

The `AdminExpenses` page currently shows all `AdminExpense` records regardless of type. We need to filter ADMIN-type from AGENT-type and show them in separate sections.

- [ ] **Step 1: Read the full `AdminExpenses.jsx`**

Read `src/pages/AdminExpenses.jsx` to understand the complete rendering of the expenses table.

- [ ] **Step 2: Add Agent Fees section at bottom**

The backend `/api/admin-expenses` already returns all expenses. Add client-side filtering:

In the component, derive agent fees from the paid list (agent fee expenses will be settled once the `settleAgent` flow marks them paid):

```jsx
// Derive agent-type from paid list
const agentFeePaid = (data?.paid || []).filter(e => e.expenseType === 'AGENT' || e.entityType === 'AGENT_EXPENSE');
```

> Note: The backend `AdminExpenseController.getAll()` returns expenses in the `paid` list when `settled=true`. We need the backend to include `expenseType` in the paid entry map.

- [ ] **Step 3: Update backend to include `expenseType` in the paid entry map**

In `AdminExpenseController.getAll()`, find the lambda that builds the paid entry for admin expenses (around line 110–123). Add:

```java
m.put("expenseType", e.getExpenseType() != null ? e.getExpenseType() : "ADMIN");
```

- [ ] **Step 4: Add Agent Fees section in `AdminExpenses.jsx`**

After the existing settled/paid expenses table, add:

```jsx
{/* Agent Fees */}
<h3 style={{ marginTop: '2rem', marginBottom: '1rem' }}>Agent Fees</h3>
{agentFeePaid.length === 0 ? (
  <p style={{ color: '#64748b' }}>No agent fee payments yet</p>
) : (
  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
    <thead>
      <tr style={{ borderBottom: '1px solid #2d3148', color: '#94a3b8', textAlign: 'left', fontSize: '0.85rem' }}>
        <th style={{ padding: '8px' }}>Agent</th>
        <th style={{ padding: '8px' }}>Amount</th>
        <th style={{ padding: '8px' }}>Notes</th>
        <th style={{ padding: '8px' }}>Settled</th>
      </tr>
    </thead>
    <tbody>
      {agentFeePaid.map(e => (
        <tr key={e.id} style={{ borderBottom: '1px solid #1e2235' }}>
          <td style={{ padding: '8px' }}>{e.who}</td>
          <td style={{ padding: '8px', color: '#4ade80' }}>{fmt(e.amount)}</td>
          <td style={{ padding: '8px', color: '#94a3b8', fontSize: '0.85rem' }}>{e.notes}</td>
          <td style={{ padding: '8px', color: '#64748b', fontSize: '0.8rem' }}>{e.settledAt || '—'}</td>
        </tr>
      ))}
    </tbody>
  </table>
)}
```

- [ ] **Step 5: Compile backend and build frontend**

```bash
cd c:/projects/tracker && rtk ./mvnw compile -q 2>&1 | tail -5
cd c:/projects/poker-frontend && rtk npm run build 2>&1 | tail -10
```

Expected: both succeed

- [ ] **Step 6: Commit**

```bash
cd c:/projects/tracker && rtk git add src/main/java/com/sevenmax/tracker/controller/AdminExpenseController.java && rtk git commit -m "feat(agent): include expenseType in admin expenses paid list"
cd c:/projects/poker-frontend && rtk git add src/pages/AdminExpenses.jsx && rtk git commit -m "feat(agent): add Agent Fees section to AdminExpenses page"
```

---

## Task 12: End-to-end smoke test (local, no production deploy)

- [ ] **Step 1: Start backend locally**

```bash
cd c:/projects/tracker && rtk ./mvnw spring-boot:run &
```

Wait for `Started TrackerApplication` in logs.

- [ ] **Step 2: Start frontend locally**

```bash
cd c:/projects/poker-frontend && rtk npm run dev &
```

- [ ] **Step 3: Manual smoke test checklist**

Open browser at `http://localhost:5173` and verify:

1. Log in as admin
2. Go to a player — verify no errors on the page
3. Edit player → check "This player is an agent" → set rake % 30 → save → confirm no errors
4. Edit another player → assign the agent → save → confirm no errors
5. Navigate to `/agents` — see the agent listed with playerCount > 0 and pendingBalance = ₪0 (no XLS uploaded yet with agentRakeShare data)
6. Upload an XLS file — verify the upload succeeds without errors
7. After upload, return to `/agents` — if any of the test player's game results were in the XLS, pending balance should be non-zero
8. Click "Settle & Pay" — verify settlement creates and agent balance returns to ₪0
9. Go to `/admin-expenses` — verify the new Agent Fees section appears with the settled payment

- [ ] **Step 4: Log in as agent player**

Verify: "Agent Portal" nav link appears. Click it — verify pending balance, history, and breakdown load without errors.

- [ ] **Step 5: Kill dev servers when done**

```bash
pkill -f "spring-boot:run"; pkill -f "npm run dev"
```

---

## Spec Coverage Check

| Spec requirement | Covered by |
|---|---|
| `is_agent`, `agent_rake_percentage`, `agent_id` on players table | Task 1 |
| `agent_rake_share`, `agent_settlement_id` on game_results | Task 1 |
| `expense_type`, `agent_settlement_id` on admin_expenses | Task 1 |
| New `agent_settlements` table | Task 2 |
| `agentRakeShare` calculated at upload, guarded by null check | Task 3 |
| Agent assignment synced from Club Overview cols D+E | Task 3 |
| `AgentService.getAgentSummary` | Task 4 |
| `AgentService.getAgentBreakdown` | Task 4 |
| `AgentService.settleAgent` — creates settlement + AdminExpense | Task 4 |
| `AgentService.getAllAgentsSummary` | Task 4 |
| `AgentController` — all 4 endpoints | Task 5 |
| `PlayerDetail.jsx` agent toggle + % input + agent dropdown | Task 6 |
| `Agents.jsx` admin table with Settle & Pay | Task 8 |
| `Agents.jsx` breakdown detail | Task 8 |
| `AgentPortal.jsx` for agent login | Task 9 |
| `AdminExpenses.jsx` Agent Fees section | Task 11 |
| `App.jsx` routes + nav | Task 10 |
| Agent Portal nav only for `isAgent=true` users | Task 10 |
