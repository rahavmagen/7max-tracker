# Club Wallets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-admin cash wallet tracking, manual bank balance correction, and a ticket asset inventory system so the club can see who holds club money and what assets are in play.

**Architecture:** Add two nullable columns to `player_transfers` (`from_admin_username`, `to_admin_username`) and two to `admin_expenses`/`club_expenses` (`paid_from_admin_username`, `paid_from_bank_account_id`). A new `WalletService` computes each admin's balance live. A new `ticket_assets` table tracks tournament ticket inventory; buying tickets auto-creates an admin expense so the buyer's wallet balance reflects the debt. Frontend gets `/club-wallets` (balances + bank correction), `/ticket-assets` (inventory + buy + grant), updated Transfer form, simplified Admin Expenses, and updated Total Profit page with clickable Bank Balance and Ticket Assets lines.

**Tech Stack:** Spring Boot 3, JPA/Hibernate, PostgreSQL, React (JSX), Vite, existing project patterns

---

## File Map

### Backend — New Files
- `src/main/java/.../service/WalletService.java` — computes admin wallet balances and history
- `src/main/java/.../controller/WalletController.java` — `GET /api/wallets/summary`, `GET /api/wallets/history`
- `src/main/java/.../controller/ImportSummaryController.java` — `PATCH /api/import-summary/bank-balance`
- `src/main/java/.../entity/TicketAsset.java` — ticket inventory entity
- `src/main/java/.../repository/TicketAssetRepository.java` — JPA repo + sumRemainingFaceValue query
- `src/main/java/.../controller/TicketAssetController.java` — buy, list, grant, summary endpoints
- `src/test/java/.../service/WalletServiceTest.java` — unit tests for balance computation

### Backend — Modified Files
- `src/main/java/.../config/SchemaMigration.java` — add new columns
- `src/main/java/.../entity/PlayerTransfer.java` — add `fromAdminUsername`, `toAdminUsername`
- `src/main/java/.../entity/AdminExpense.java` — add `paidFromAdminUsername`, `paidFromBankAccountId`
- `src/main/java/.../entity/ClubExpense.java` — add `paidFromAdminUsername`, `paidFromBankAccountId`
- `src/main/java/.../service/PlayerTransferService.java` — pass admin fields in `createTransfer`
- `src/main/java/.../controller/PlayerTransferController.java` — extract admin fields from request
- `src/main/java/.../controller/AdminExpenseController.java` — add `PATCH /{id}/pay`, simplify GET
- `src/main/java/.../controller/ClubExpenseController.java` — add `PATCH /{id}/pay`

### Frontend — New Files
- `src/pages/ClubWallets.jsx` — new page: per-admin balances, history, bank balance correction
- `src/pages/TicketAssets.jsx` — new page: ticket inventory, buy form, grant action

### Frontend — Modified Files
- `src/api.js` — add `getWalletSummary`, `getWalletHistory`, `payAdminExpense`, `payClubExpense`
- `src/pages/Transfers.jsx` — add "Handled by admin" dropdown when CLUB is on either side
- `src/pages/AdminExpenses.jsx` — remove VAT split, replace settle buttons with single Pay
- `src/pages/TotalProfit.jsx` — make Bank Balance line a clickable link to `/club-wallets`
- `src/App.jsx` — add route and nav link for Club Wallets

---

## Task 1: Schema Migration

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/config/SchemaMigration.java`

- [ ] **Step 1: Add the 6 new columns in SchemaMigration**

Open `SchemaMigration.java` and add these SQL blocks at the end of the `@PostConstruct` method (after the last existing `ensureColumn` or similar call). Follow the exact pattern already used in that file.

```java
// Club Wallets: admin attribution on transfers
ensureColumn("player_transfers", "from_admin_username", "VARCHAR(255)");
ensureColumn("player_transfers", "to_admin_username", "VARCHAR(255)");

// Club Wallets: payment source on expenses
ensureColumn("admin_expenses", "paid_from_admin_username", "VARCHAR(255)");
ensureColumn("admin_expenses", "paid_from_bank_account_id", "BIGINT");
ensureColumn("club_expenses", "paid_from_admin_username", "VARCHAR(255)");
ensureColumn("club_expenses", "paid_from_bank_account_id", "BIGINT");
```

> Check how `ensureColumn` is already implemented in `SchemaMigration.java` — it uses `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`. Use exactly the same helper method.

- [ ] **Step 2: Restart the local Spring Boot server and verify no errors in the log**

```bash
# Stop the running mvn process (Ctrl+C) then:
cd /c/projects/tracker && rtk mvn spring-boot:run
```

Check `C:/projects/tracker/logs/app.log` — should see `SchemaMigration` lines with no errors.

- [ ] **Step 3: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/config/SchemaMigration.java
rtk git commit -m "feat: schema migration for club wallet admin attribution columns"
```

---

## Task 2: Update Entities

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/entity/PlayerTransfer.java`
- Modify: `src/main/java/com/sevenmax/tracker/entity/AdminExpense.java`
- Modify: `src/main/java/com/sevenmax/tracker/entity/ClubExpense.java`

- [ ] **Step 1: Add fields to PlayerTransfer**

Add after the `createdByUsername` field:

```java
// Which admin physically handled the club's side of this transfer (nullable)
private String fromAdminUsername;
private String toAdminUsername;
```

- [ ] **Step 2: Add fields to AdminExpense**

Add after the `vatType` field:

```java
// Which admin wallet or bank account paid this expense (set when paid)
private String paidFromAdminUsername;
private Long paidFromBankAccountId;
```

- [ ] **Step 3: Add fields to ClubExpense**

Add after the `vatType` field:

```java
// Which admin wallet or bank account paid this expense (set when paid)
private String paidFromAdminUsername;
private Long paidFromBankAccountId;
```

- [ ] **Step 4: Verify compile**

```bash
cd /c/projects/tracker && rtk mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/entity/PlayerTransfer.java \
  src/main/java/com/sevenmax/tracker/entity/AdminExpense.java \
  src/main/java/com/sevenmax/tracker/entity/ClubExpense.java
rtk git commit -m "feat: add admin attribution fields to transfer and expense entities"
```

---

## Task 3: WalletService with Unit Tests

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/service/WalletService.java`
- Create: `src/test/java/com/sevenmax/tracker/service/WalletServiceTest.java`

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/sevenmax/tracker/service/WalletServiceTest.java`:

```java
package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock PlayerTransferRepository transferRepository;
    @Mock AdminExpenseRepository adminExpenseRepository;
    @Mock ClubExpenseRepository clubExpenseRepository;
    @Mock UserRepository userRepository;
    @Mock BankAccountRepository bankAccountRepository;

    WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(
            transferRepository, adminExpenseRepository,
            clubExpenseRepository, userRepository, bankAccountRepository
        );
    }

    private PlayerTransfer transfer(String fromAdmin, String toAdmin, int amount) {
        PlayerTransfer t = new PlayerTransfer();
        t.setFromAdminUsername(fromAdmin);
        t.setToAdminUsername(toAdmin);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTransferDate(LocalDate.now());
        return t;
    }

    private AdminExpense paidExpense(String paidFromAdmin, int amount) {
        AdminExpense e = new AdminExpense();
        e.setAdminUsername("SomeAdmin");
        e.setAmount(BigDecimal.valueOf(amount));
        e.setPaidFromAdminUsername(paidFromAdmin);
        e.setSettled(true);
        return e;
    }

    @Test
    void balance_receivedCash_increasesBalance() {
        // Player pays club, admin "Alice" receives the cash
        PlayerTransfer t = transfer(null, "Alice", 1000);
        when(transferRepository.findAll()).thenReturn(List.of(t));
        when(adminExpenseRepository.findAll()).thenReturn(List.of());
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        BigDecimal balance = walletService.computeBalance("Alice");

        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void balance_gaveCashOut_decreasesBalance() {
        // Admin "Alice" paid a player from club cash
        PlayerTransfer t = transfer("Alice", null, 500);
        when(transferRepository.findAll()).thenReturn(List.of(t));
        when(adminExpenseRepository.findAll()).thenReturn(List.of());
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        BigDecimal balance = walletService.computeBalance("Alice");

        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(-500));
    }

    @Test
    void balance_adminToAdminTransfer_correctForBoth() {
        // Alice → Bob: Alice -1000, Bob +1000
        PlayerTransfer t = transfer("Alice", "Bob", 1000);
        when(transferRepository.findAll()).thenReturn(List.of(t));
        when(adminExpenseRepository.findAll()).thenReturn(List.of());
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        assertThat(walletService.computeBalance("Alice")).isEqualByComparingTo(BigDecimal.valueOf(-1000));
        assertThat(walletService.computeBalance("Bob")).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void balance_paidExpense_decreasesBalance() {
        // Alice paid an admin expense of 200 from her wallet
        when(transferRepository.findAll()).thenReturn(List.of());
        when(adminExpenseRepository.findAll()).thenReturn(List.of(paidExpense("Alice", 200)));
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        BigDecimal balance = walletService.computeBalance("Alice");

        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(-200));
    }

    @Test
    void balance_noActivity_isZero() {
        when(transferRepository.findAll()).thenReturn(List.of());
        when(adminExpenseRepository.findAll()).thenReturn(List.of());
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        assertThat(walletService.computeBalance("Alice")).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/projects/tracker && rtk mvn test -pl . -Dtest=WalletServiceTest
```

Expected: FAIL — `WalletService` does not exist yet.

- [ ] **Step 3: Implement WalletService**

Create `src/main/java/com/sevenmax/tracker/service/WalletService.java`:

```java
package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final PlayerTransferRepository transferRepository;
    private final AdminExpenseRepository adminExpenseRepository;
    private final ClubExpenseRepository clubExpenseRepository;
    private final UserRepository userRepository;
    private final BankAccountRepository bankAccountRepository;

    /** Compute how much club cash this admin is currently holding. */
    public BigDecimal computeBalance(String adminUsername) {
        List<PlayerTransfer> transfers = transferRepository.findAll();
        List<AdminExpense> adminExpenses = adminExpenseRepository.findAll();
        List<ClubExpense> clubExpenses = clubExpenseRepository.findAll();

        BigDecimal balance = BigDecimal.ZERO;

        for (PlayerTransfer t : transfers) {
            if (adminUsername.equals(t.getToAdminUsername())) {
                balance = balance.add(t.getAmount());
            }
            if (adminUsername.equals(t.getFromAdminUsername())) {
                balance = balance.subtract(t.getAmount());
            }
        }

        for (AdminExpense e : adminExpenses) {
            if (Boolean.TRUE.equals(e.getSettled())
                    && adminUsername.equals(e.getPaidFromAdminUsername())) {
                balance = balance.subtract(e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO);
            }
        }

        for (ClubExpense e : clubExpenses) {
            if (e.isSettled()
                    && adminUsername.equals(e.getPaidFromAdminUsername())) {
                balance = balance.subtract(e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO);
            }
        }

        return balance;
    }

    /** Summary: all admin usernames with their balance. */
    public List<Map<String, Object>> getAdminSummaries() {
        List<String> adminUsernames = userRepository.findAll().stream()
            .filter(u -> u.getRole() == User.Role.ADMIN || u.getRole() == User.Role.MANAGER)
            .filter(u -> Boolean.TRUE.equals(u.getActive()))
            .map(User::getUsername)
            .sorted()
            .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (String username : adminUsernames) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adminUsername", username);
            m.put("balance", computeBalance(username));
            result.add(m);
        }
        return result;
    }

    /** History: all wallet events (transfers with admin attribution + paid expenses). */
    public List<Map<String, Object>> getHistory() {
        List<Map<String, Object>> events = new ArrayList<>();

        // Transfers with any admin attribution
        transferRepository.findAll().stream()
            .filter(t -> t.getFromAdminUsername() != null || t.getToAdminUsername() != null)
            .forEach(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "TRANSFER");
                m.put("date", t.getTransferDate() != null ? t.getTransferDate().toString() : "");
                m.put("fromAdmin", t.getFromAdminUsername());
                m.put("toAdmin", t.getToAdminUsername());
                m.put("fromPlayer", t.getFromPlayer() != null ? t.getFromPlayer().getUsername() : null);
                m.put("toPlayer", t.getToPlayer() != null ? t.getToPlayer().getUsername() : null);
                m.put("amount", t.getAmount());
                m.put("method", t.getMethod() != null ? t.getMethod().toString() : "");
                m.put("notes", t.getNotes());
                m.put("createdBy", t.getCreatedByUsername());
                events.add(m);
            });

        // Paid admin expenses with admin payment source
        adminExpenseRepository.findAll().stream()
            .filter(e -> Boolean.TRUE.equals(e.getSettled()) && e.getPaidFromAdminUsername() != null)
            .forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "EXPENSE_PAID");
                m.put("date", e.getSettledAt() != null ? e.getSettledAt().toString() : "");
                m.put("fromAdmin", e.getPaidFromAdminUsername());
                m.put("toAdmin", null);
                m.put("description", "Expense: " + (e.getNotes() != null ? e.getNotes() : e.getAdminUsername()));
                m.put("amount", e.getAmount());
                m.put("notes", e.getNotes());
                events.add(m);
            });

        // Paid club expenses with admin payment source
        clubExpenseRepository.findAll().stream()
            .filter(e -> e.isSettled() && e.getPaidFromAdminUsername() != null)
            .forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "EXPENSE_PAID");
                m.put("date", e.getSettledAt() != null ? e.getSettledAt().toString() : "");
                m.put("fromAdmin", e.getPaidFromAdminUsername());
                m.put("toAdmin", null);
                m.put("description", "Club Expense: " + e.getDescription());
                m.put("amount", e.getAmount());
                m.put("notes", e.getDescription());
                events.add(m);
            });

        // Sort newest first
        events.sort(Comparator.comparing(
            (Map<String, Object> m) -> m.get("date") != null ? m.get("date").toString() : ""
        ).reversed());

        return events;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /c/projects/tracker && rtk mvn test -pl . -Dtest=WalletServiceTest
```

Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/service/WalletService.java \
  src/test/java/com/sevenmax/tracker/service/WalletServiceTest.java
rtk git commit -m "feat: WalletService with balance computation and history"
```

---

## Task 4: WalletController

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/controller/WalletController.java`

- [ ] **Step 1: Create WalletController**

```java
package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        List<Map<String, Object>> admins = walletService.getAdminSummaries();
        BigDecimal adminTotal = admins.stream()
            .map(m -> (BigDecimal) m.get("balance"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("admins", admins);
        response.put("adminTotal", adminTotal);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(walletService.getHistory());
    }

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }
}
```

> **Note on bank accounts:** `BankAccount` has no balance field — its balance is XLS-derived and stored in `ImportSummary.bankDeposits`. Including it here would duplicate complex existing logic. **Bank accounts are excluded from v1 Club Wallets** — only admin wallets are shown. This is a known gap vs. the spec's "Club Total = admin wallets + bank accounts"; bank accounts can be added in v2.

- [ ] **Step 2: Verify compile and test endpoint locally**

```bash
cd /c/projects/tracker && rtk mvn compile
```

Expected: `BUILD SUCCESS`

Then open browser DevTools on `http://localhost:5173` and run:
```js
fetch('http://localhost:8080/api/wallets/summary', {
  headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') }
}).then(r => r.json()).then(console.log)
```

Expected: JSON with `admins` array (all zeros until data is entered) and `adminTotal: 0`.

- [ ] **Step 3: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/controller/WalletController.java
rtk git commit -m "feat: WalletController with summary and history endpoints"
```

---

## Task 5: Update Transfer Creation (Backend)

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/service/PlayerTransferService.java` (around line 44-70)
- Modify: `src/main/java/com/sevenmax/tracker/controller/PlayerTransferController.java` (around line 28-44)

- [ ] **Step 1: Update PlayerTransferService.createTransfer signature**

Change the `createTransfer` method signature and body to accept admin usernames:

```java
@Transactional
public PlayerTransfer createTransfer(Long fromPlayerId, Long fromBankAccountId, Long toPlayerId,
        Long toBankAccountId, BigDecimal amount, Transaction.Method method, String notes,
        String createdBy, String fromAdminUsername, String toAdminUsername) {
    Player fromPlayer = fromPlayerId != null ? playerRepository.findById(fromPlayerId).orElse(null) : null;
    Player toPlayer = toPlayerId != null ? playerRepository.findById(toPlayerId).orElse(null) : null;
    BankAccount fromBankAccount = fromBankAccountId != null ? bankAccountRepository.findById(fromBankAccountId).orElse(null) : null;
    BankAccount toBankAccount = toBankAccountId != null ? bankAccountRepository.findById(toBankAccountId).orElse(null) : null;

    PlayerTransfer transfer = new PlayerTransfer();
    transfer.setFromPlayer(fromPlayer);
    transfer.setToPlayer(toPlayer);
    transfer.setFromBankAccount(fromBankAccount);
    transfer.setToBankAccount(toBankAccount);
    transfer.setAmount(amount);
    transfer.setMethod(method);
    transfer.setNotes(notes);
    transfer.setTransferDate(LocalDate.now());
    transfer.setCreatedByUsername(createdBy);
    transfer.setFromAdminUsername(fromAdminUsername);
    transfer.setToAdminUsername(toAdminUsername);
```

> Keep the rest of the method body unchanged — just add the two `setFromAdminUsername`/`setToAdminUsername` lines and update the signature. The existing caller in the controller will need updating too (next step).

- [ ] **Step 2: Update PlayerTransferController.create to extract admin fields**

In `PlayerTransferController.java`, update the `create` method:

```java
@PostMapping
public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
    try {
        Long fromPlayerId = body.get("fromPlayerId") != null ? ((Number) body.get("fromPlayerId")).longValue() : null;
        Long fromBankAccountId = body.get("fromBankAccountId") != null ? ((Number) body.get("fromBankAccountId")).longValue() : null;
        Long toPlayerId = body.get("toPlayerId") != null ? ((Number) body.get("toPlayerId")).longValue() : null;
        Long toBankAccountId = body.get("toBankAccountId") != null ? ((Number) body.get("toBankAccountId")).longValue() : null;
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        Transaction.Method method = Transaction.Method.valueOf(body.get("method").toString());
        String notes = (String) body.get("notes");
        String fromAdminUsername = (String) body.get("fromAdminUsername");
        String toAdminUsername = (String) body.get("toAdminUsername");
        String createdBy = auth != null ? auth.getName() : null;
        var transfer = transferService.createTransfer(fromPlayerId, fromBankAccountId, toPlayerId, toBankAccountId,
                amount, method, notes, createdBy, fromAdminUsername, toAdminUsername);
        return ResponseEntity.ok(transferService.toDto(transfer));
    } catch (Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
cd /c/projects/tracker && rtk mvn compile
```

Expected: `BUILD SUCCESS` — if `createTransfer` is called elsewhere in the service, find those calls and add `null, null` for the two new parameters.

- [ ] **Step 4: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/service/PlayerTransferService.java \
  src/main/java/com/sevenmax/tracker/controller/PlayerTransferController.java
rtk git commit -m "feat: pass admin attribution fields through transfer creation"
```

---

## Task 6: Admin Expense Pay Endpoint + Simplified GET

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/controller/AdminExpenseController.java`

- [ ] **Step 1: Add PATCH /{id}/pay endpoint**

Add this method to `AdminExpenseController.java` (after the existing `settle` method):

```java
// PATCH /admin-expenses/{id}/pay — pay an expense, recording which wallet it came from
@PatchMapping("/{id}/pay")
public ResponseEntity<?> pay(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
    return expenseRepository.findById(id).map(expense -> {
        expense.setSettled(true);
        expense.setSettledBy(auth != null ? auth.getName() : "system");
        expense.setSettledAt(LocalDate.now());
        if (body.get("paidFromAdminUsername") != null) {
            expense.setPaidFromAdminUsername(body.get("paidFromAdminUsername").toString());
        }
        if (body.get("paidFromBankAccountId") != null) {
            expense.setPaidFromBankAccountId(((Number) body.get("paidFromBankAccountId")).longValue());
        }
        if (expense.getAmount() != null) {
            deductFromBank(expense.getAmount());
            createBankHistoryEntry(expense, auth);
        }
        return ResponseEntity.ok(expenseRepository.save(expense));
    }).orElse(ResponseEntity.notFound().build());
}
```

- [ ] **Step 2: Simplify GET response — remove VAT split, add unified paid list**

In the `getAll()` method, replace the `paidNoVat`/`paidWithVat` logic with a single `paid` list. Find the section starting at `// Paid expenses split by vatType` and replace through the end of the method:

```java
        // Single unified paid list (no VAT split)
        List<Map<String, Object>> paid = new ArrayList<>();

        all.stream()
            .filter(e -> Boolean.TRUE.equals(e.getSettled()))
            .forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("entityType", "ADMIN_EXPENSE");
                m.put("who", e.getAdminUsername());
                m.put("amount", e.getAmount());
                m.put("notes", e.getNotes());
                m.put("expenseDate", e.getExpenseDate() != null ? e.getExpenseDate().toString() : null);
                m.put("settledAt", e.getSettledAt() != null ? e.getSettledAt().toString() : null);
                m.put("paidFromAdminUsername", e.getPaidFromAdminUsername());
                paid.add(m);
            });

        clubExpenseRepository.findBySettledTrue().forEach(ce -> {
            String name = ce.getPaidBy() == ClubExpense.PaidBy.ADMIN
                ? (ce.getAdminUser() != null ? ce.getAdminUser() : "Admin")
                : ("🏦 " + (ce.getBankAccount() != null ? ce.getBankAccount().getName() : "Club"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ce.getId());
            m.put("entityType", "CLUB_EXPENSE");
            m.put("who", name);
            m.put("amount", ce.getAmount());
            m.put("notes", ce.getDescription());
            m.put("expenseDate", ce.getExpenseDate() != null ? ce.getExpenseDate().toString() : null);
            m.put("settledAt", ce.getSettledAt() != null ? ce.getSettledAt().toString() : null);
            m.put("paidFromAdminUsername", ce.getPaidFromAdminUsername());
            paid.add(m);
        });

        paid.sort(Comparator.comparing(m -> m.get("expenseDate") != null ? m.get("expenseDate").toString() : ""));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("admins", result);
        response.put("grandTotal", grandTotal);
        response.put("paid", paid);
        return ResponseEntity.ok(response);
```

> Also remove the now-unused `buildPaidEntry` and `addToPaidList` private methods if nothing else calls them.

- [ ] **Step 3: Compile**

```bash
cd /c/projects/tracker && rtk mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/controller/AdminExpenseController.java
rtk git commit -m "feat: add pay endpoint and simplify admin expense GET response"
```

---

## Task 7: Club Expense Pay Endpoint

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/controller/ClubExpenseController.java`

- [ ] **Step 1: Add PATCH /{id}/pay endpoint**

Read `ClubExpenseController.java` first to understand its settle method, then add a new `pay` method after it:

```java
// PATCH /club-expenses/{id}/pay
@PatchMapping("/{id}/pay")
public ResponseEntity<?> pay(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
    return clubExpenseRepository.findById(id).map(e -> {
        e.setSettled(true);
        e.setSettledBy(auth != null ? auth.getName() : "system");
        e.setSettledAt(LocalDate.now());
        if (body.get("paidFromAdminUsername") != null) {
            e.setPaidFromAdminUsername(body.get("paidFromAdminUsername").toString());
        }
        if (body.get("paidFromBankAccountId") != null) {
            e.setPaidFromBankAccountId(((Number) body.get("paidFromBankAccountId")).longValue());
        }
        return ResponseEntity.ok(clubExpenseRepository.save(e));
    }).orElse(ResponseEntity.notFound().build());
}
```

> Check if `ClubExpenseController` has access to `clubExpenseRepository` (it should already be injected).

- [ ] **Step 2: Compile**

```bash
cd /c/projects/tracker && rtk mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/controller/ClubExpenseController.java
rtk git commit -m "feat: add pay endpoint to ClubExpenseController"
```

---

## Task 8: Frontend API Updates

**Files:**
- Modify: `src/api.js`

- [ ] **Step 1: Add new API calls**

Add these exports to `src/api.js`:

```js
export const getWalletSummary = () => api.get('/wallets/summary');
export const getWalletHistory = () => api.get('/wallets/history');
export const payAdminExpense = (id, data) => api.patch(`/admin-expenses/${id}/pay`, data);
export const payClubExpense = (id, data) => api.patch(`/club-expenses/${id}/pay`, data);
export const setBankBalance = (amount) => api.patch('/import-summary/bank-balance', { amount });
export const getTicketAssets = () => api.get('/ticket-assets');
export const getTicketAssetsSummary = () => api.get('/ticket-assets/summary');
export const buyTickets = (data) => api.post('/ticket-assets', data);
export const grantTicket = (id, data) => api.post(`/ticket-assets/${id}/grant`, data);
```

- [ ] **Step 2: Commit**

```bash
rtk git add src/api.js
rtk git commit -m "feat: add wallet, pay, bank balance, and ticket asset API calls"
```

---

## Task 9: Transfer Form — Admin Dropdown

**Files:**
- Modify: `src/pages/Transfers.jsx`

- [ ] **Step 1: Add admin dropdown state**

In the `Transfers` component, the transfer form state is `transferForm`. Add `fromAdminUsername` and `toAdminUsername` to its initial value:

```js
const [transferForm, setTransferForm] = useState({
  fromId: '', toId: '', method: '', amount: '', notes: '',
  fromAdminUsername: '', toAdminUsername: ''
});
```

- [ ] **Step 2: Add the admin dropdown component inline in the transfer form**

Find where `transferForm.fromId` is rendered (the From `PlayerSelect`). After that `PlayerSelect`, add:

```jsx
{/* Show admin picker when CLUB is the sender */}
{transferForm.fromId === 'CLUB' && (
  <div style={{ marginTop: '0.5rem' }}>
    <label style={{ color: '#94a3b8', fontSize: '0.85rem', display: 'block', marginBottom: '0.25rem' }}>
      Handled by admin (From)
    </label>
    <select
      value={transferForm.fromAdminUsername}
      onChange={e => setTransferForm(p => ({ ...p, fromAdminUsername: e.target.value }))}
      style={{ width: '100%', background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '6px', padding: '0.5rem' }}
    >
      <option value="">— select admin —</option>
      {adminUsers.map(a => (
        <option key={a.username} value={a.username}>{a.username}</option>
      ))}
    </select>
  </div>
)}
```

Do the same after the To `PlayerSelect` for `toAdminUsername` when `transferForm.toId === 'CLUB'`.

- [ ] **Step 3: Pass admin fields in the submit handler**

Find `handleTransferSubmit` and the `createTransfer(...)` call. Add the admin fields to the payload:

```js
const payload = {
  ...resolveParticipant(transferForm.fromId),    // existing logic
  ...resolveToParticipant(transferForm.toId),    // existing logic
  method: transferForm.method,
  amount: parseFloat(transferForm.amount),
  notes: transferForm.notes || null,
  fromAdminUsername: transferForm.fromAdminUsername || null,
  toAdminUsername: transferForm.toAdminUsername || null,
};
```

> Read the existing `handleTransferSubmit` carefully — adapt the payload construction to match the existing pattern exactly. The key addition is `fromAdminUsername` and `toAdminUsername`.

- [ ] **Step 4: Reset admin fields on form clear**

Find where `transferForm` is reset after a successful submit. Add the two new fields:

```js
setTransferForm({ fromId: '', toId: '', method: '', amount: '', notes: '', fromAdminUsername: '', toAdminUsername: '' });
```

- [ ] **Step 5: Test manually**

Open `http://localhost:5173/transfers`, select CLUB as From or To, verify the admin dropdown appears. Submit a transfer with an admin selected, verify it saves without error.

- [ ] **Step 6: Commit**

```bash
rtk git add src/pages/Transfers.jsx
rtk git commit -m "feat: add admin attribution dropdown to transfer form when CLUB is selected"
```

---

## Task 10: Admin Expenses — Simplified UI with Pay Button

**Files:**
- Modify: `src/pages/AdminExpenses.jsx`

- [ ] **Step 1: Update imports**

Replace `settleClubExpense` and `settleAdminExpense` imports with the new `payAdminExpense` and `payClubExpense`:

```js
import { getAdminExpenses, deleteAdminExpense, updateAdminExpense, getPromotions,
         updateClubExpense, deleteClubExpense, payAdminExpense, payClubExpense,
         getAdminUsers, getBankAccounts } from '../api';
```

- [ ] **Step 2: Add state for pay picker and load admin users + bank accounts**

Add these state variables:

```js
const [adminUsers, setAdminUsers] = useState([]);
const [bankAccounts, setBankAccounts] = useState([]);
const [paying, setPaying] = useState(null); // { id, type } when pay picker is open
const [paySource, setPaySource] = useState(''); // 'ADMIN:username' or 'BANK:id'
```

In the `load` function (the `useEffect`), add fetches:

```js
Promise.all([
  getAdminExpenses(),
  getPromotions(),
  getAdminUsers(),
  getBankAccounts(),
]).then(([expRes, promoRes, adminRes, bankRes]) => {
  setData(expRes.data);
  setPromotions(promoRes.data);
  setAdminUsers(adminRes.data);
  setBankAccounts(bankRes.data);
  setLoading(false);
});
```

- [ ] **Step 3: Implement handlePay using new endpoints**

Replace the existing `handlePay` function with:

```js
const handlePay = async (id, type) => {
  if (!paySource) {
    setMsg({ type: 'error', text: 'Select who paid this expense' });
    return;
  }
  try {
    let payload = {};
    if (paySource.startsWith('ADMIN:')) {
      payload.paidFromAdminUsername = paySource.slice(6);
    } else if (paySource.startsWith('BANK:')) {
      payload.paidFromBankAccountId = parseInt(paySource.slice(5));
    }
    if (type === 'CLUB_EXPENSE') {
      await payClubExpense(id, payload);
    } else {
      await payAdminExpense(id, payload);
    }
    setMsg({ type: 'success', text: 'Paid' });
    setPaying(null);
    setPaySource('');
    load();
  } catch {
    setMsg({ type: 'error', text: 'Failed to pay' });
  }
};
```

- [ ] **Step 4: Replace VAT buttons with single Pay button + inline picker**

In the per-admin table rows where the "No VAT" and "VAT" buttons appear, replace them with:

```jsx
{paying?.id === entry.id ? (
  <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
    <select
      value={paySource}
      onChange={e => setPaySource(e.target.value)}
      style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '4px 8px', fontSize: '0.82rem' }}
    >
      <option value="">— paid from —</option>
      {adminUsers.map(a => (
        <option key={a.username} value={`ADMIN:${a.username}`}>{a.username}</option>
      ))}
      {bankAccounts.map(b => (
        <option key={b.id} value={`BANK:${b.id}`}>🏦 {b.name}</option>
      ))}
    </select>
    <button style={payBtnStyle('#4ade80')} onClick={() => handlePay(entry.id, entry.type || 'ADMIN_EXPENSE')}>
      Confirm
    </button>
    <button style={payBtnStyle('#64748b')} onClick={() => { setPaying(null); setPaySource(''); }}>
      Cancel
    </button>
  </div>
) : (
  <button style={payBtnStyle('#4ade80')} onClick={() => setPaying({ id: entry.id, type: entry.type || 'ADMIN_EXPENSE' })}>
    Pay
  </button>
)}
```

- [ ] **Step 5: Replace paid section rendering (remove VAT split)**

Remove the two `renderPaidSection` calls for VAT/No VAT. Replace with a single unified paid section using `data.paid` (the new response field):

```jsx
{data?.paid?.length > 0 && (
  <div className="card" style={{ marginBottom: '1rem', borderColor: '#22c55e' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }}
      onClick={() => toggleExpand('__paid')}>
      <strong style={{ color: '#22c55e', fontSize: '1.05rem' }}>✓ Paid Expenses</strong>
      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
        <strong style={{ color: '#22c55e' }}>
          {fmt(data.paid.reduce((s, e) => s + Number(e.amount || 0), 0))}
        </strong>
        <span style={{ color: '#64748b', fontSize: '0.85rem' }}>{expandedAdmins['__paid'] ? '▲' : '▼'}</span>
      </div>
    </div>
    {expandedAdmins['__paid'] && (
      <div style={{ marginTop: '1rem', borderTop: '1px solid #2d3148', paddingTop: '0.75rem', overflowX: 'auto' }}>
        <table style={{ width: '100%' }}>
          <thead>
            <tr>
              <th style={{ textAlign: 'left', color: '#64748b', fontWeight: 500, paddingBottom: '0.5rem' }}>Date</th>
              <th style={{ textAlign: 'left', color: '#64748b', fontWeight: 500, paddingBottom: '0.5rem' }}>Who</th>
              <th style={{ textAlign: 'left', color: '#64748b', fontWeight: 500, paddingBottom: '0.5rem' }}>Description</th>
              <th style={{ textAlign: 'right', color: '#64748b', fontWeight: 500, paddingBottom: '0.5rem' }}>Amount</th>
              <th style={{ textAlign: 'left', color: '#64748b', fontWeight: 500, paddingBottom: '0.5rem' }}>Paid On</th>
              <th style={{ textAlign: 'left', color: '#64748b', fontWeight: 500, paddingBottom: '0.5rem' }}>Paid From</th>
            </tr>
          </thead>
          <tbody>
            {data.paid.map(e => (
              <tr key={`${e.entityType}-${e.id}`}>
                <td style={{ color: '#94a3b8', fontSize: '0.85rem', paddingTop: '0.4rem' }}>{e.expenseDate || '—'}</td>
                <td style={{ color: '#a5b4fc', fontSize: '0.85rem' }}>{e.who || '—'}</td>
                <td style={{ color: '#e2e8f0', fontSize: '0.85rem' }}>{e.notes || '—'}</td>
                <td style={{ textAlign: 'right', color: '#22c55e', fontWeight: 600 }}>{fmt(e.amount)}</td>
                <td style={{ color: '#64748b', fontSize: '0.85rem' }}>{e.settledAt || '—'}</td>
                <td style={{ color: '#94a3b8', fontSize: '0.85rem' }}>{e.paidFromAdminUsername || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )}
  </div>
)}
```

- [ ] **Step 6: Remove renderPaidSection function and VAT-related state**

Delete the `renderPaidSection` helper function and the `paidNoVat`, `paidWithVat`, `paidNoVatTotal`, `paidWithVatTotal` variables — they're no longer used.

- [ ] **Step 7: Test manually**

Open `http://localhost:5173/admin-expenses`. Verify:
- Unsettled expenses show "Pay" button (not VAT/No VAT)
- Clicking "Pay" shows admin + bank account picker
- Selecting source and clicking Confirm marks it paid
- Paid section shows unified list

- [ ] **Step 8: Commit**

```bash
rtk git add src/pages/AdminExpenses.jsx
rtk git commit -m "feat: simplify admin expenses UI - single Pay button with paid-from picker"
```

---

## Task 11: New ClubWallets Page

**Files:**
- Create: `src/pages/ClubWallets.jsx`

- [ ] **Step 1: Create the page**

```jsx
import { useState, useEffect } from 'react';
import { getWalletSummary, getWalletHistory } from '../api';

const fmt = (n) => {
  if (n === undefined || n === null) return '₪0';
  const abs = Math.abs(Number(n)).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (Number(n) < 0 ? '-₪' : '₪') + abs;
};

export default function ClubWallets() {
  const [summary, setSummary] = useState(null);
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [filterAdmin, setFilterAdmin] = useState('');
  const [filterFrom, setFilterFrom] = useState('');
  const [filterTo, setFilterTo] = useState('');

  useEffect(() => {
    Promise.all([getWalletSummary(), getWalletHistory()]).then(([sumRes, histRes]) => {
      setSummary(sumRes.data);
      setHistory(histRes.data);
      setLoading(false);
    }).catch(() => setLoading(false));
  }, []);

  const admins = summary?.admins || [];
  const adminTotal = Number(summary?.adminTotal || 0);

  const filteredHistory = history.filter(h => {
    if (filterAdmin && h.fromAdmin !== filterAdmin && h.toAdmin !== filterAdmin) return false;
    if (filterFrom && h.date && h.date < filterFrom) return false;
    if (filterTo && h.date && h.date > filterTo) return false;
    return true;
  });

  const allAdmins = [...new Set(history.flatMap(h => [h.fromAdmin, h.toAdmin].filter(Boolean)))].sort();

  return (
    <div>
      <div className="page-header">
        <h1>Club Wallets</h1>
        <span style={{ color: '#22c55e', fontSize: '1.4rem', fontWeight: 700 }}>{fmt(adminTotal)}</span>
      </div>

      {loading ? (
        <div style={{ color: '#64748b', padding: '2rem' }}>Loading...</div>
      ) : (
        <>
          {/* Admin balances summary */}
          <div className="card" style={{ marginBottom: '1.5rem' }}>
            <h2 style={{ marginBottom: '1rem' }}>Admin Wallets</h2>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr style={{ color: '#64748b', fontSize: '0.85rem' }}>
                    <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>Admin</th>
                    <th style={{ textAlign: 'right', paddingBottom: '0.5rem' }}>Balance</th>
                  </tr>
                </thead>
                <tbody>
                  {admins.map(a => (
                    <tr key={a.adminUsername}>
                      <td style={{ color: '#e2e8f0', paddingTop: '0.4rem' }}>{a.adminUsername}</td>
                      <td style={{ textAlign: 'right', fontWeight: 600,
                        color: Number(a.balance) >= 0 ? '#22c55e' : '#ef4444' }}>
                        {fmt(a.balance)}
                      </td>
                    </tr>
                  ))}
                  <tr style={{ borderTop: '2px solid #334155' }}>
                    <td style={{ color: '#e2e8f0', fontWeight: 700, paddingTop: '0.5rem' }}>Total</td>
                    <td style={{ textAlign: 'right', fontWeight: 700, fontSize: '1.1rem',
                      color: adminTotal >= 0 ? '#22c55e' : '#ef4444', paddingTop: '0.5rem' }}>
                      {fmt(adminTotal)}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          {/* History */}
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem', flexWrap: 'wrap', gap: '0.5rem' }}>
              <h2 style={{ margin: 0 }}>History</h2>
              <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
                <select value={filterAdmin} onChange={e => setFilterAdmin(e.target.value)}
                  style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.25rem 0.5rem', fontSize: '0.85rem' }}>
                  <option value="">All admins</option>
                  {allAdmins.map(a => <option key={a} value={a}>{a}</option>)}
                </select>
                <input type="date" value={filterFrom} onChange={e => setFilterFrom(e.target.value)}
                  style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.25rem 0.5rem', fontSize: '0.85rem' }} />
                <span style={{ color: '#64748b' }}>→</span>
                <input type="date" value={filterTo} onChange={e => setFilterTo(e.target.value)}
                  style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.25rem 0.5rem', fontSize: '0.85rem' }} />
                {(filterAdmin || filterFrom || filterTo) && (
                  <button onClick={() => { setFilterAdmin(''); setFilterFrom(''); setFilterTo(''); }}
                    style={{ background: 'transparent', color: '#64748b', border: '1px solid #334155', borderRadius: '4px', padding: '0.2rem 0.6rem', fontSize: '0.85rem', cursor: 'pointer' }}>
                    Clear
                  </button>
                )}
              </div>
            </div>

            {filteredHistory.length === 0 ? (
              <div style={{ color: '#64748b', textAlign: 'center', padding: '1.5rem' }}>
                No wallet events yet. Assign admins when recording transfers or paying expenses.
              </div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr style={{ color: '#64748b', fontSize: '0.85rem' }}>
                      <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>Date</th>
                      <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>Type</th>
                      <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>From</th>
                      <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>To</th>
                      <th style={{ textAlign: 'right', paddingBottom: '0.5rem' }}>Amount</th>
                      <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>Notes</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredHistory.map((h, i) => (
                      <tr key={i}>
                        <td style={{ color: '#94a3b8', fontSize: '0.85rem', paddingTop: '0.4rem' }}>{h.date || '—'}</td>
                        <td>
                          <span style={{ fontSize: '0.75rem', borderRadius: '4px', padding: '2px 6px',
                            background: h.type === 'TRANSFER' ? '#1e3a5f' : '#14532d',
                            color: h.type === 'TRANSFER' ? '#60a5fa' : '#4ade80' }}>
                            {h.type === 'TRANSFER' ? 'Transfer' : 'Expense Paid'}
                          </span>
                        </td>
                        <td style={{ color: '#e2e8f0', fontSize: '0.85rem' }}>
                          {h.fromAdmin || h.fromPlayer || '—'}
                        </td>
                        <td style={{ color: '#e2e8f0', fontSize: '0.85rem' }}>
                          {h.toAdmin || h.toPlayer || '—'}
                        </td>
                        <td style={{ textAlign: 'right', color: '#22c55e', fontWeight: 600 }}>{fmt(h.amount)}</td>
                        <td style={{ color: '#64748b', fontSize: '0.85rem' }}>{h.notes || h.description || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
rtk git add src/pages/ClubWallets.jsx
rtk git commit -m "feat: new ClubWallets page with admin balances and history"
```

---

## Task 12: App.jsx — Route + Nav

**Files:**
- Modify: `src/App.jsx`

- [ ] **Step 1: Add imports and routes**

Add the imports near the other page imports:

```js
import ClubWallets from './pages/ClubWallets';
import TicketAssets from './pages/TicketAssets';
```

Add the routes alongside the other admin routes:

```jsx
<Route path="/club-wallets" element={<ClubWallets />} />
<Route path="/ticket-assets" element={<TicketAssets />} />
```

- [ ] **Step 2: Add nav links**

Find the `<NavLink to="/bank-balance">Bank Balance</NavLink>` in the nav and add siblings:

```jsx
<NavLink to="/club-wallets">Club Wallets</NavLink>
<NavLink to="/ticket-assets">Ticket Assets</NavLink>
```

- [ ] **Step 3: Commit**

```bash
rtk git add src/App.jsx
rtk git commit -m "feat: add Club Wallets and Ticket Assets routes and nav links"
```

---

## Task 13: TotalProfit — Clickable Bank Balance + Ticket Assets Line

**Files:**
- Modify: `src/pages/TotalProfit.jsx`

- [ ] **Step 1: Add useNavigate and ticket assets fetch**

At the top of the component, add:

```js
import { useNavigate } from 'react-router-dom';
import { getTicketAssetsSummary } from '../api';
// inside component:
const navigate = useNavigate();
const [ticketAssets, setTicketAssets] = useState(0);

useEffect(() => {
  getTicketAssetsSummary().then(r => setTicketAssets(Number(r.data.totalFaceValue) || 0));
}, []);
```

- [ ] **Step 2: Make the Bank Balance row clickable**

Find the table row that renders `bankDeposits`. It currently looks like:

```jsx
<td className="positive"><strong>{fmt(bankDeposits)}</strong></td>
```

Wrap the value in a clickable span:

```jsx
<td className="positive">
  <strong
    style={{ cursor: 'pointer', textDecoration: 'underline dotted' }}
    onClick={() => navigate('/club-wallets')}
    title="Click to view Club Wallets"
  >
    {fmt(bankDeposits)}
  </strong>
</td>
```

- [ ] **Step 3: Add Ticket Assets row**

Find the table row for Bank Balance and add a new row directly below it:

```jsx
<tr>
  <td>Ticket Assets</td>
  <td className="positive">
    <strong
      style={{ cursor: 'pointer', textDecoration: 'underline dotted' }}
      onClick={() => navigate('/ticket-assets')}
      title="Click to view Ticket Assets"
    >
      {fmt(ticketAssets)}
    </strong>
  </td>
</tr>
```

- [ ] **Step 4: Test manually**

Open `http://localhost:5173/total-profit`. Verify Bank Balance is clickable (navigates to `/club-wallets`) and Ticket Assets row appears.

- [ ] **Step 5: Commit**

```bash
rtk git add src/pages/TotalProfit.jsx
rtk git commit -m "feat: add clickable Bank Balance link and Ticket Assets line to Total Profit"
```

---

## Task 14: Final Integration Test

- [ ] **Step 1: Full backend test suite**

```bash
cd /c/projects/tracker && rtk mvn test
```

Expected: All tests pass including `WalletServiceTest`.

- [ ] **Step 2: Manual end-to-end walkthrough**

1. Go to `/transfers` → record a player-to-CLUB transfer → select an admin → verify submitted without error
2. Go to `/club-wallets` → verify the admin's balance increased
3. Go to `/admin-expenses` → mark an expense as paid → select admin from picker → verify moves to Paid section
4. Go to `/club-wallets` → verify the admin's balance decreased
5. Go to `/total-profit` → click Bank Balance → verify navigates to `/club-wallets`
6. Go to `/total-profit` → click Ticket Assets → verify navigates to `/ticket-assets`

- [ ] **Step 3: Final commit if any loose ends**

```bash
rtk git add -A
rtk git commit -m "feat: club wallets - complete integration"
```

---

## Task 15: Bank Balance Manual Correction

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/controller/ImportSummaryController.java`
- Modify: `src/pages/ClubWallets.jsx`

- [ ] **Step 1: Create ImportSummaryController**

```java
package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.PlayerTransfer;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/import-summary")
@RequiredArgsConstructor
public class ImportSummaryController {

    private final ImportSummaryRepository importSummaryRepository;
    private final PlayerTransferRepository playerTransferRepository;

    @PatchMapping("/bank-balance")
    public ResponseEntity<?> setBankBalance(@RequestBody Map<String, Object> body, Authentication auth) {
        String amtStr = body.get("amount") != null ? body.get("amount").toString() : null;
        if (amtStr == null) return ResponseEntity.badRequest().body(Map.of("error", "amount required"));
        BigDecimal amount;
        try { amount = new BigDecimal(amtStr); } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount"));
        }

        ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
        summary.setId(1L);
        summary.setBankDeposits(amount);
        summary.setLastUpdated(java.time.LocalDateTime.now());
        importSummaryRepository.save(summary);

        // Audit trail: record as a transfer adjustment so bank history reflects the correction
        PlayerTransfer adj = new PlayerTransfer();
        adj.setAmount(amount);
        adj.setMethod(Transaction.Method.CASH);
        adj.setNotes("Manual bank balance correction");
        adj.setTransferDate(LocalDate.now());
        adj.setCreatedByUsername(auth != null ? auth.getName() : "system");
        adj.setConfirmed(true);
        playerTransferRepository.save(adj);

        return ResponseEntity.ok(Map.of("bankDeposits", amount));
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /c/projects/tracker && rtk mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Add bank balance correction UI to ClubWallets.jsx**

Find the bank account section in `ClubWallets.jsx` (where bank account balances are listed). Add a correction form below the bank totals:

```jsx
const [newBankBalance, setNewBankBalance] = useState('');
const [bankSaving, setBankSaving] = useState(false);

const handleSetBankBalance = async () => {
  if (!newBankBalance) return;
  setBankSaving(true);
  try {
    await setBankBalance(newBankBalance);
    setNewBankBalance('');
    load(); // reload wallet summary
  } finally {
    setBankSaving(false);
  }
};

// In JSX, below the bank account table:
<div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
  <span style={{ color: '#94a3b8', fontSize: '0.85rem' }}>Set bank balance:</span>
  <input
    type="number"
    value={newBankBalance}
    onChange={e => setNewBankBalance(e.target.value)}
    placeholder="e.g. 12500"
    style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0',
      borderRadius: '4px', padding: '0.3rem 0.5rem', width: '140px', fontSize: '0.85rem' }}
  />
  <button onClick={handleSetBankBalance} disabled={bankSaving}
    style={{ background: '#1e40af', color: '#fff', border: 'none', borderRadius: '4px',
      padding: '0.3rem 0.8rem', fontSize: '0.85rem', cursor: 'pointer' }}>
    {bankSaving ? 'Saving…' : 'Set'}
  </button>
</div>
```

Also add `setBankBalance` to the imports from `../api`.

- [ ] **Step 4: Test manually**

1. Start the backend
2. Go to `/club-wallets`
3. Enter a value in "Set bank balance" and click Set
4. Verify the bank account balance updates to the new value

- [ ] **Step 5: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/controller/ImportSummaryController.java \
            src/pages/ClubWallets.jsx
rtk git commit -m "feat: manual bank balance correction endpoint and UI"
```

---

## Task 16: Ticket Assets — Entity, Repository, Schema

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/config/SchemaMigration.java`
- Create: `src/main/java/com/sevenmax/tracker/entity/TicketAsset.java`
- Create: `src/main/java/com/sevenmax/tracker/repository/TicketAssetRepository.java`

- [ ] **Step 1: Add ticket_assets table to SchemaMigration**

Add at the end of the `@PostConstruct` method in `SchemaMigration.java`:

```java
// Ticket Assets
ensureTable("ticket_assets",
    "id BIGSERIAL PRIMARY KEY, " +
    "cost_per_ticket NUMERIC(12,2) NOT NULL, " +
    "face_value_per_ticket NUMERIC(12,2) NOT NULL, " +
    "quantity_total INTEGER NOT NULL, " +
    "quantity_remaining INTEGER NOT NULL, " +
    "buyer_admin_username VARCHAR(255) NOT NULL, " +
    "purchase_date DATE NOT NULL, " +
    "notes VARCHAR(500), " +
    "created_at TIMESTAMP DEFAULT NOW()"
);
```

> Check `SchemaMigration.java` for how `ensureTable` is implemented — it uses `CREATE TABLE IF NOT EXISTS`. Use the same helper. If no `ensureTable` helper exists, add the DDL as a `jdbcTemplate.execute(...)` call guarded by `IF NOT EXISTS`.

- [ ] **Step 2: Create TicketAsset entity**

```java
package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_assets")
@Data
public class TicketAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal costPerTicket;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal faceValuePerTicket;

    @Column(nullable = false)
    private Integer quantityTotal;

    @Column(nullable = false)
    private Integer quantityRemaining;

    @Column(nullable = false)
    private String buyerAdminUsername;

    @Column(nullable = false)
    private LocalDate purchaseDate;

    private String notes;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

- [ ] **Step 3: Create TicketAssetRepository**

```java
package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.TicketAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface TicketAssetRepository extends JpaRepository<TicketAsset, Long> {

    @Query("SELECT COALESCE(SUM(t.faceValuePerTicket * t.quantityRemaining), 0) FROM TicketAsset t")
    BigDecimal sumRemainingFaceValue();
}
```

- [ ] **Step 4: Compile**

```bash
cd /c/projects/tracker && rtk mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/config/SchemaMigration.java \
            src/main/java/com/sevenmax/tracker/entity/TicketAsset.java \
            src/main/java/com/sevenmax/tracker/repository/TicketAssetRepository.java
rtk git commit -m "feat: TicketAsset entity, repository, and schema migration"
```

---

## Task 17: Ticket Assets — Controller

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/controller/TicketAssetController.java`

- [ ] **Step 1: Create TicketAssetController**

```java
package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.AdminExpense;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.TicketAsset;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.AdminExpenseRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TicketAssetRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ticket-assets")
@RequiredArgsConstructor
public class TicketAssetController {

    private final TicketAssetRepository ticketAssetRepository;
    private final AdminExpenseRepository adminExpenseRepository;
    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;

    // GET /api/ticket-assets — full list
    @GetMapping
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(ticketAssetRepository.findAll());
    }

    // GET /api/ticket-assets/summary — total face value remaining (for profit page)
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        BigDecimal total = ticketAssetRepository.sumRemainingFaceValue();
        return ResponseEntity.ok(Map.of("totalFaceValue", total));
    }

    // POST /api/ticket-assets — buy tickets
    @PostMapping
    public ResponseEntity<?> buy(@RequestBody Map<String, Object> body, Authentication auth) {
        String adminUsername = (String) body.get("buyerAdminUsername");
        String costStr = body.get("costPerTicket") != null ? body.get("costPerTicket").toString() : null;
        String faceStr = body.get("faceValuePerTicket") != null ? body.get("faceValuePerTicket").toString() : null;
        Integer qty = body.get("quantityTotal") != null ? ((Number) body.get("quantityTotal")).intValue() : null;
        String dateStr = (String) body.get("purchaseDate");
        String notes = (String) body.get("notes");

        if (adminUsername == null || costStr == null || faceStr == null || qty == null || dateStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "buyerAdminUsername, costPerTicket, faceValuePerTicket, quantityTotal, purchaseDate are required"));
        }

        BigDecimal cost, face;
        try {
            cost = new BigDecimal(costStr);
            face = new BigDecimal(faceStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount"));
        }

        // Create inventory row
        TicketAsset asset = new TicketAsset();
        asset.setCostPerTicket(cost);
        asset.setFaceValuePerTicket(face);
        asset.setQuantityTotal(qty);
        asset.setQuantityRemaining(qty);
        asset.setBuyerAdminUsername(adminUsername);
        asset.setPurchaseDate(LocalDate.parse(dateStr));
        asset.setNotes(notes);
        ticketAssetRepository.save(asset);

        // Auto-create admin expense so buyer's wallet shows the debt
        AdminExpense expense = new AdminExpense();
        expense.setAdminUsername(adminUsername);
        expense.setAmount(cost.multiply(BigDecimal.valueOf(qty)));
        expense.setNotes("Ticket purchase: " + qty + "x ₪" + face + " tickets"
                + (notes != null ? " — " + notes : ""));
        expense.setExpenseDate(LocalDate.parse(dateStr));
        expense.setCreatedBy(auth != null ? auth.getName() : "system");
        adminExpenseRepository.save(expense);

        return ResponseEntity.ok(asset);
    }

    // POST /api/ticket-assets/{id}/grant — grant one ticket to a player
    @PostMapping("/{id}/grant")
    public ResponseEntity<?> grant(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        return ticketAssetRepository.findById(id).map(asset -> {
            if (asset.getQuantityRemaining() <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "No tickets remaining"));
            }

            Long playerId = body.get("playerId") != null ? ((Number) body.get("playerId")).longValue() : null;
            if (playerId == null) return ResponseEntity.badRequest().body(Map.of("error", "playerId required"));

            Player player = playerRepository.findById(playerId).orElse(null);
            if (player == null) return ResponseEntity.badRequest().body(Map.of("error", "Player not found"));

            // Decrement inventory
            asset.setQuantityRemaining(asset.getQuantityRemaining() - 1);
            ticketAssetRepository.save(asset);

            // Create chip deduction transaction
            Transaction tx = new Transaction();
            tx.setPlayer(player);
            tx.setAmount(asset.getFaceValuePerTicket().negate());
            tx.setType(Transaction.Type.TICKET_GRANT);
            tx.setNotes("Ticket grant — ₪" + asset.getFaceValuePerTicket() + " ticket");
            tx.setDate(LocalDate.now());
            tx.setCreatedBy(auth != null ? auth.getName() : "system");
            tx.setSourceRef("TICKET:" + asset.getId());
            transactionRepository.save(tx);

            return ResponseEntity.ok(Map.of("success", true, "quantityRemaining", asset.getQuantityRemaining()));
        }).orElse(ResponseEntity.notFound().build());
    }
}
```

> **Note:** Before writing this controller, check `Transaction.java` for the exact field names (`date` vs `transactionDate`, `type` enum values, `createdBy` vs `createdByUsername`). Adjust accordingly.

- [ ] **Step 2: Add TICKET_GRANT to Transaction.Type enum if missing**

Read `Transaction.java`. If `Type` enum does not have `TICKET_GRANT`, add it:

```java
public enum Type { /* existing values */, TICKET_GRANT }
```

- [ ] **Step 3: Compile**

```bash
cd /c/projects/tracker && rtk mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
rtk git add src/main/java/com/sevenmax/tracker/controller/TicketAssetController.java \
            src/main/java/com/sevenmax/tracker/entity/Transaction.java
rtk git commit -m "feat: TicketAssetController with buy, list, grant, and summary endpoints"
```

---

## Task 18: Ticket Assets — Frontend Page

**Files:**
- Create: `src/pages/TicketAssets.jsx`

- [ ] **Step 1: Create TicketAssets.jsx**

```jsx
import { useEffect, useState } from 'react';
import { getTicketAssets, buyTickets, grantTicket } from '../api';

const fmt = (n) => '₪' + Number(n || 0).toLocaleString('he-IL', { minimumFractionDigits: 0, maximumFractionDigits: 0 });

export default function TicketAssets() {
  const [assets, setAssets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [adminUsers, setAdminUsers] = useState([]);
  const [players, setPlayers] = useState([]);
  const [buyForm, setBuyForm] = useState({
    buyerAdminUsername: '', costPerTicket: '', faceValuePerTicket: '',
    quantityTotal: '', purchaseDate: new Date().toISOString().slice(0, 10), notes: ''
  });
  const [grantState, setGrantState] = useState({}); // { [assetId]: { playerId, saving } }
  const [buying, setBuying] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const [assetsRes, adminsRes, playersRes] = await Promise.all([
        getTicketAssets(),
        fetch('/api/admin-expenses/admin-users', { headers: { Authorization: `Bearer ${localStorage.getItem('token')}` } }).then(r => r.json()),
        fetch('/api/players', { headers: { Authorization: `Bearer ${localStorage.getItem('token')}` } }).then(r => r.json()),
      ]);
      setAssets(assetsRes.data);
      setAdminUsers(adminsRes);
      setPlayers(playersRes);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleBuy = async () => {
    setBuying(true);
    try {
      await buyTickets({ ...buyForm, quantityTotal: Number(buyForm.quantityTotal) });
      setBuyForm(f => ({ ...f, costPerTicket: '', faceValuePerTicket: '', quantityTotal: '', notes: '' }));
      load();
    } catch (e) {
      alert(e.response?.data?.error || 'Error buying tickets');
    } finally {
      setBuying(false);
    }
  };

  const handleGrant = async (assetId) => {
    const state = grantState[assetId] || {};
    if (!state.playerId) return alert('Select a player first');
    setGrantState(s => ({ ...s, [assetId]: { ...state, saving: true } }));
    try {
      await grantTicket(assetId, { playerId: state.playerId });
      setGrantState(s => ({ ...s, [assetId]: { playerId: '', saving: false } }));
      load();
    } catch (e) {
      alert(e.response?.data?.error || 'Error granting ticket');
      setGrantState(s => ({ ...s, [assetId]: { ...state, saving: false } }));
    }
  };

  const totalFaceValue = assets.reduce((sum, a) => sum + Number(a.faceValuePerTicket) * Number(a.quantityRemaining), 0);

  if (loading) return <div className="page-container"><p style={{ color: '#64748b' }}>Loading…</p></div>;

  return (
    <div className="page-container">
      <h1>Ticket Assets</h1>

      {/* Buy form */}
      <div className="card" style={{ marginBottom: '1.5rem' }}>
        <h2 style={{ marginTop: 0 }}>Buy Tickets</h2>
        <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div>
            <label style={{ color: '#94a3b8', fontSize: '0.8rem', display: 'block', marginBottom: '0.25rem' }}>Admin</label>
            <select value={buyForm.buyerAdminUsername} onChange={e => setBuyForm(f => ({ ...f, buyerAdminUsername: e.target.value }))}
              style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.4rem 0.6rem' }}>
              <option value="">— select —</option>
              {adminUsers.map(a => <option key={a.username} value={a.username}>{a.username}</option>)}
            </select>
          </div>
          <div>
            <label style={{ color: '#94a3b8', fontSize: '0.8rem', display: 'block', marginBottom: '0.25rem' }}>Cost/ticket</label>
            <input type="number" value={buyForm.costPerTicket} onChange={e => setBuyForm(f => ({ ...f, costPerTicket: e.target.value }))}
              placeholder="950" style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.4rem 0.6rem', width: '100px' }} />
          </div>
          <div>
            <label style={{ color: '#94a3b8', fontSize: '0.8rem', display: 'block', marginBottom: '0.25rem' }}>Face value/ticket</label>
            <input type="number" value={buyForm.faceValuePerTicket} onChange={e => setBuyForm(f => ({ ...f, faceValuePerTicket: e.target.value }))}
              placeholder="1000" style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.4rem 0.6rem', width: '100px' }} />
          </div>
          <div>
            <label style={{ color: '#94a3b8', fontSize: '0.8rem', display: 'block', marginBottom: '0.25rem' }}>Qty</label>
            <input type="number" value={buyForm.quantityTotal} onChange={e => setBuyForm(f => ({ ...f, quantityTotal: e.target.value }))}
              placeholder="10" style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.4rem 0.6rem', width: '70px' }} />
          </div>
          <div>
            <label style={{ color: '#94a3b8', fontSize: '0.8rem', display: 'block', marginBottom: '0.25rem' }}>Date</label>
            <input type="date" value={buyForm.purchaseDate} onChange={e => setBuyForm(f => ({ ...f, purchaseDate: e.target.value }))}
              style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.4rem 0.6rem' }} />
          </div>
          <div>
            <label style={{ color: '#94a3b8', fontSize: '0.8rem', display: 'block', marginBottom: '0.25rem' }}>Notes</label>
            <input type="text" value={buyForm.notes} onChange={e => setBuyForm(f => ({ ...f, notes: e.target.value }))}
              placeholder="optional" style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.4rem 0.6rem', width: '140px' }} />
          </div>
          <button onClick={handleBuy} disabled={buying || !buyForm.buyerAdminUsername || !buyForm.costPerTicket || !buyForm.faceValuePerTicket || !buyForm.quantityTotal}
            style={{ background: '#1e40af', color: '#fff', border: 'none', borderRadius: '4px', padding: '0.5rem 1rem', cursor: 'pointer', fontWeight: 600 }}>
            {buying ? 'Buying…' : 'Buy'}
          </button>
        </div>
      </div>

      {/* Inventory */}
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
          <h2 style={{ margin: 0 }}>Inventory</h2>
          <span style={{ color: '#22c55e', fontWeight: 700, fontSize: '1.1rem' }}>
            Total Asset Value: {fmt(totalFaceValue)}
          </span>
        </div>

        {assets.length === 0 ? (
          <div style={{ color: '#64748b', textAlign: 'center', padding: '1.5rem' }}>No tickets in inventory.</div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr style={{ color: '#64748b', fontSize: '0.85rem' }}>
                  <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>Buyer</th>
                  <th style={{ textAlign: 'right', paddingBottom: '0.5rem' }}>Cost</th>
                  <th style={{ textAlign: 'right', paddingBottom: '0.5rem' }}>Face Value</th>
                  <th style={{ textAlign: 'right', paddingBottom: '0.5rem' }}>Remaining</th>
                  <th style={{ textAlign: 'right', paddingBottom: '0.5rem' }}>Total</th>
                  <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>Date</th>
                  <th style={{ textAlign: 'left', paddingBottom: '0.5rem' }}>Notes</th>
                  <th style={{ paddingBottom: '0.5rem' }}>Grant</th>
                </tr>
              </thead>
              <tbody>
                {assets.map(a => {
                  const gs = grantState[a.id] || { playerId: '' };
                  return (
                    <tr key={a.id}>
                      <td style={{ color: '#e2e8f0', fontSize: '0.9rem' }}>{a.buyerAdminUsername}</td>
                      <td style={{ textAlign: 'right', color: '#94a3b8' }}>{fmt(a.costPerTicket)}</td>
                      <td style={{ textAlign: 'right', color: '#22c55e', fontWeight: 600 }}>{fmt(a.faceValuePerTicket)}</td>
                      <td style={{ textAlign: 'right', color: a.quantityRemaining === 0 ? '#64748b' : '#e2e8f0', fontWeight: 600 }}>
                        {a.quantityRemaining} / {a.quantityTotal}
                      </td>
                      <td style={{ textAlign: 'right', color: '#94a3b8' }}>{fmt(Number(a.faceValuePerTicket) * a.quantityRemaining)}</td>
                      <td style={{ color: '#64748b', fontSize: '0.85rem' }}>{a.purchaseDate}</td>
                      <td style={{ color: '#64748b', fontSize: '0.85rem' }}>{a.notes || '—'}</td>
                      <td>
                        {a.quantityRemaining > 0 ? (
                          <div style={{ display: 'flex', gap: '0.4rem', alignItems: 'center' }}>
                            <select value={gs.playerId} onChange={e => setGrantState(s => ({ ...s, [a.id]: { ...gs, playerId: e.target.value } }))}
                              style={{ background: '#1e293b', border: '1px solid #334155', color: '#e2e8f0', borderRadius: '4px', padding: '0.25rem 0.4rem', fontSize: '0.8rem', maxWidth: '140px' }}>
                              <option value="">— player —</option>
                              {players.map(p => <option key={p.id} value={p.id}>{p.username}</option>)}
                            </select>
                            <button onClick={() => handleGrant(a.id)} disabled={gs.saving || !gs.playerId}
                              style={{ background: '#065f46', color: '#fff', border: 'none', borderRadius: '4px', padding: '0.25rem 0.6rem', fontSize: '0.8rem', cursor: 'pointer' }}>
                              {gs.saving ? '…' : 'Grant'}
                            </button>
                          </div>
                        ) : (
                          <span style={{ color: '#64748b', fontSize: '0.8rem' }}>Depleted</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Test manually**

1. Go to `/ticket-assets`
2. Fill in the Buy Tickets form and submit — verify a new row appears in the inventory
3. Check `/admin-expenses` — verify an expense entry was auto-created for the buyer admin
4. Click Grant on an inventory row, select a player, confirm — verify remaining count decrements

- [ ] **Step 3: Commit**

```bash
rtk git add src/pages/TicketAssets.jsx
rtk git commit -m "feat: TicketAssets page with buy form, inventory table, and grant action"
```
