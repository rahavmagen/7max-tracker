# Manual Bank Balance Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an admin/manager manually correct the tracked club Bank balance (`ImportSummary.bankDeposits`) to a new absolute value, any number of times, with each correction visible and clearly labeled in the existing Bank history drill-down on the Club Wallets page.

**Architecture:** Finish wiring up an existing, never-used mechanism instead of building new infrastructure: `ImportSummaryController.setBankBalance` (backend, `PATCH /api/import-summary/bank-balance`) and `setBankBalance()` (frontend, `api.js`) already exist but are called from nowhere in the UI, and the audit trail they write (a `PlayerTransfer` with `method=ADJUSTMENT`) is invisible in Bank history because the query behind it excludes bare adjustment rows. This plan: (1) hardens the existing endpoint (auth gate, validation, stores a signed delta instead of an absolute value so it reconciles correctly), (2) makes the Bank history query and endpoint include and label these rows distinctly, (3) adds a UI control on Club Wallets to call it.

**Tech Stack:** Spring Boot / JPA (backend, `c:\projects\tracker`), React + Vite (frontend, `c:\projects\poker-frontend`), JUnit5 + Mockito + AssertJ for backend tests.

---

### Task 1: Extend the Bank-related transfers query to include manual adjustments

**Files:**
- Modify: `c:\projects\tracker\src\main\java\com\sevenmax\tracker\repository\PlayerTransferRepository.java:23-24`

- [ ] **Step 1: Update the `@Query` annotation on `findBankRelatedTransfers`**

Current code (lines 23-24):
```java
    @Query("SELECT t FROM PlayerTransfer t WHERE t.fromBankAccount IS NOT NULL OR t.toBankAccount IS NOT NULL OR (t.fromPlayer IS NULL AND t.toPlayer IS NOT NULL) OR (t.toPlayer IS NULL AND t.fromPlayer IS NOT NULL) ORDER BY t.transferDate ASC, t.createdAt ASC")
    List<PlayerTransfer> findBankRelatedTransfers();
```

Replace with:
```java
    @Query("SELECT t FROM PlayerTransfer t WHERE t.fromBankAccount IS NOT NULL OR t.toBankAccount IS NOT NULL OR (t.fromPlayer IS NULL AND t.toPlayer IS NOT NULL) OR (t.toPlayer IS NULL AND t.fromPlayer IS NOT NULL) OR t.method = com.sevenmax.tracker.entity.Transaction.Method.ADJUSTMENT ORDER BY t.transferDate ASC, t.createdAt ASC")
    List<PlayerTransfer> findBankRelatedTransfers();
```

This is a pure JPQL change (custom query methods in this repo have no dedicated tests — verified via `find /c/projects/tracker/src/test -iname "*.java"`, only service-layer tests exist). It will be verified manually in Task 5 against the running local backend.

- [ ] **Step 2: Compile to check for syntax errors**

Run: `cd /c/projects/tracker && ./mvnw.cmd compile -q`
Expected: no output, exit code 0 (compiles cleanly; devtools will pick this up automatically since the backend is already running locally).

- [ ] **Step 3: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/repository/PlayerTransferRepository.java
git commit -m "Include manual bank adjustments in bank-related transfers query"
```

---

### Task 2: Harden `ImportSummaryController.setBankBalance` (auth, validation, signed delta)

**Files:**
- Modify: `c:\projects\tracker\src\main\java\com\sevenmax\tracker\controller\ImportSummaryController.java`
- Test: `c:\projects\tracker\src\test\java\com\sevenmax\tracker\controller\ImportSummaryControllerTest.java` (new)

The existing endpoint stores the **absolute new balance** as the audit `PlayerTransfer.amount`. Verified against production (`SELECT ... WHERE method='ADJUSTMENT'` returned zero rows — this endpoint has never been called), so it's safe to change what `amount` means here to a **signed delta** (`newBalance - previousBalance`), which is what Task 3's history reconciliation needs. The endpoint also currently has no role gate (any authenticated user, including players, could call it) and no validation against a negative balance.

- [ ] **Step 1: Write the failing test file**

Create `c:\projects\tracker\src\test\java\com\sevenmax\tracker\controller\ImportSummaryControllerTest.java`:

```java
package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.PlayerTransfer;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImportSummaryControllerTest {

    @Mock ImportSummaryRepository importSummaryRepository;
    @Mock PlayerTransferRepository playerTransferRepository;

    ImportSummaryController controller;

    @BeforeEach
    void setUp() {
        controller = new ImportSummaryController(importSummaryRepository, playerTransferRepository);
    }

    private Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken("admin1", null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private Authentication playerAuth() {
        return new UsernamePasswordAuthenticationToken("player1", null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_PLAYER")));
    }

    @Test
    void rejectsPlayerRole() {
        ResponseEntity<?> response = controller.setBankBalance(Map.of("bankBalance", "1000"), playerAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(importSummaryRepository, playerTransferRepository);
    }

    @Test
    void rejectsMissingBankBalance() {
        ResponseEntity<?> response = controller.setBankBalance(Map.of(), adminAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejectsNegativeBankBalance() {
        ResponseEntity<?> response = controller.setBankBalance(Map.of("bankBalance", "-50"), adminAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void storesSignedDeltaAndUpdatesSummary() {
        ImportSummary existing = new ImportSummary();
        existing.setId(1L);
        existing.setBankDeposits(BigDecimal.valueOf(17473.84));
        when(importSummaryRepository.findById(1L)).thenReturn(Optional.of(existing));

        ResponseEntity<?> response = controller.setBankBalance(
            Map.of("bankBalance", "20000", "notes", "reconciled with bank app"), adminAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<ImportSummary> summaryCaptor = ArgumentCaptor.forClass(ImportSummary.class);
        verify(importSummaryRepository).save(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getBankDeposits()).isEqualByComparingTo(BigDecimal.valueOf(20000));

        ArgumentCaptor<PlayerTransfer> auditCaptor = ArgumentCaptor.forClass(PlayerTransfer.class);
        verify(playerTransferRepository).save(auditCaptor.capture());
        PlayerTransfer audit = auditCaptor.getValue();
        assertThat(audit.getMethod()).isEqualTo(Transaction.Method.ADJUSTMENT);
        assertThat(audit.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(2526.16)); // 20000 - 17473.84
        assertThat(audit.getNotes()).contains("17473.84").contains("20000").contains("reconciled with bank app");
        assertThat(audit.getConfirmed()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /c/projects/tracker && ./mvnw.cmd test -Dtest=ImportSummaryControllerTest -q`
Expected: FAIL — `rejectsPlayerRole` and `rejectsNegativeBankBalance` fail because the current controller has no role gate or negative-balance check (calls succeed with 200 instead of 403/400).

- [ ] **Step 3: Replace `ImportSummaryController.java` with the hardened implementation**

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

    // PATCH /api/import-summary/bank-balance — manually correct bankDeposits to a specific value.
    // Records the delta as an audit PlayerTransfer (method=ADJUSTMENT) so it shows up in Bank history.
    @PatchMapping("/bank-balance")
    public ResponseEntity<?> setBankBalance(@RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();

        if (body.get("bankBalance") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "bankBalance is required"));
        }
        BigDecimal newBalance;
        try {
            newBalance = new BigDecimal(body.get("bankBalance").toString());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid bankBalance"));
        }
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "bankBalance cannot be negative"));
        }

        ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
        summary.setId(1L);
        BigDecimal previousBalance = summary.getBankDeposits() != null ? summary.getBankDeposits() : BigDecimal.ZERO;
        BigDecimal delta = newBalance.subtract(previousBalance);

        summary.setBankDeposits(newBalance);
        summary.setLastUpdated(java.time.LocalDateTime.now());
        importSummaryRepository.save(summary);

        String customNotes = body.get("notes") != null ? body.get("notes").toString().trim() : "";
        String autoNote = "Balance corrected: " + previousBalance + " -> " + newBalance;
        String notes = customNotes.isEmpty() ? autoNote : autoNote + " - " + customNotes;

        // Audit log entry as a PlayerTransfer with method=ADJUSTMENT; amount stores the signed delta
        // so it merges correctly into the Bank history reconciliation (findBankRelatedTransfers).
        PlayerTransfer audit = new PlayerTransfer();
        audit.setAmount(delta);
        audit.setMethod(Transaction.Method.ADJUSTMENT);
        audit.setNotes(notes);
        audit.setTransferDate(LocalDate.now());
        audit.setCreatedByUsername(auth != null ? auth.getName() : "system");
        audit.setConfirmed(true);
        playerTransferRepository.save(audit);

        return ResponseEntity.ok(Map.of("previousBalance", previousBalance, "newBalance", newBalance, "delta", delta));
    }

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /c/projects/tracker && ./mvnw.cmd test -Dtest=ImportSummaryControllerTest -q`
Expected: PASS (all 4 tests green, no output on success with `-q`).

- [ ] **Step 5: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/controller/ImportSummaryController.java src/test/java/com/sevenmax/tracker/controller/ImportSummaryControllerTest.java
git commit -m "Harden manual bank-balance endpoint: auth gate, validation, signed delta"
```

---

### Task 3: Show manual adjustments distinctly in the Bank history endpoint

**Files:**
- Modify: `c:\projects\tracker\src\main\java\com\sevenmax\tracker\controller\PlayerTransferController.java:126-168`

- [ ] **Step 1: Replace `getBankHistory` and add a `bankDelta` helper**

Current code (lines 126-168):
```java
    @GetMapping("/bank-history")
    public ResponseEntity<?> getBankHistory() {
        var transfers = transferRepository.findBankRelatedTransfers();
        var summary = importSummaryRepository.findById(1L).orElse(null);

        // Compute XLS base = current bankDeposits - sum of all bank transfer deltas
        java.math.BigDecimal transferSum = java.math.BigDecimal.ZERO;
        for (var t : transfers) {
            boolean toBank = t.getToBankAccount() != null || (t.getToPlayer() == null && t.getFromPlayer() != null);
            transferSum = toBank ? transferSum.add(t.getAmount()) : transferSum.subtract(t.getAmount());
        }
        java.math.BigDecimal currentBank = summary != null && summary.getBankDeposits() != null ? summary.getBankDeposits() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal xlsBase = currentBank.subtract(transferSum);

        var rows = new java.util.ArrayList<Map<String, Object>>();
        if (xlsBase.compareTo(java.math.BigDecimal.ZERO) != 0) {
            rows.add(Map.of("type", "XLS", "description", "XLS base (מיקום הכסף)", "delta", xlsBase, "date", ""));
        }
        for (var t : transfers) {
            boolean toBank = t.getToBankAccount() != null || (t.getToPlayer() == null && t.getFromPlayer() != null);
            java.math.BigDecimal delta = toBank ? t.getAmount() : t.getAmount().negate();
            String fromName = t.getFromPlayer() != null ? t.getFromPlayer().getUsername()
                    : (t.getFromBankAccount() != null ? t.getFromBankAccount().getName() : "CLUB");
            String toName = t.getToPlayer() != null ? t.getToPlayer().getUsername()
                    : (t.getToBankAccount() != null ? t.getToBankAccount().getName() : "CLUB");
            var row = new java.util.HashMap<String, Object>();
            row.put("id", t.getId());
            row.put("type", "TRANSFER");
            row.put("date", t.getTransferDate() != null ? t.getTransferDate().toString() : "");
            row.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "");
            row.put("fromName", fromName);
            row.put("toName", toName);
            row.put("fromPlayerId", t.getFromPlayer() != null ? t.getFromPlayer().getId() : null);
            row.put("toPlayerId", t.getToPlayer() != null ? t.getToPlayer().getId() : null);
            row.put("delta", delta);
            row.put("method", t.getMethod() != null ? t.getMethod().toString() : "");
            row.put("notes", t.getNotes() != null ? t.getNotes() : "");
            row.put("createdBy", t.getCreatedByUsername() != null ? t.getCreatedByUsername() : "");
            rows.add(row);
        }
        java.util.Collections.reverse(rows); // newest first
        return ResponseEntity.ok(Map.of("rows", rows, "total", currentBank));
    }
}
```

Replace with:
```java
    @GetMapping("/bank-history")
    public ResponseEntity<?> getBankHistory() {
        var transfers = transferRepository.findBankRelatedTransfers();
        var summary = importSummaryRepository.findById(1L).orElse(null);

        // Compute XLS base = current bankDeposits - sum of all bank transfer/adjustment deltas
        java.math.BigDecimal transferSum = java.math.BigDecimal.ZERO;
        for (var t : transfers) {
            transferSum = transferSum.add(bankDelta(t));
        }
        java.math.BigDecimal currentBank = summary != null && summary.getBankDeposits() != null ? summary.getBankDeposits() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal xlsBase = currentBank.subtract(transferSum);

        var rows = new java.util.ArrayList<Map<String, Object>>();
        if (xlsBase.compareTo(java.math.BigDecimal.ZERO) != 0) {
            rows.add(Map.of("type", "XLS", "description", "XLS base (מיקום הכסף)", "delta", xlsBase, "date", ""));
        }
        for (var t : transfers) {
            boolean isAdjustment = t.getMethod() == Transaction.Method.ADJUSTMENT;
            java.math.BigDecimal delta = bankDelta(t);
            String fromName = isAdjustment ? null : (t.getFromPlayer() != null ? t.getFromPlayer().getUsername()
                    : (t.getFromBankAccount() != null ? t.getFromBankAccount().getName() : "CLUB"));
            String toName = isAdjustment ? null : (t.getToPlayer() != null ? t.getToPlayer().getUsername()
                    : (t.getToBankAccount() != null ? t.getToBankAccount().getName() : "CLUB"));
            var row = new java.util.HashMap<String, Object>();
            row.put("id", t.getId());
            row.put("type", isAdjustment ? "ADJUSTMENT" : "TRANSFER");
            row.put("date", t.getTransferDate() != null ? t.getTransferDate().toString() : "");
            row.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "");
            row.put("fromName", fromName);
            row.put("toName", toName);
            row.put("fromPlayerId", t.getFromPlayer() != null ? t.getFromPlayer().getId() : null);
            row.put("toPlayerId", t.getToPlayer() != null ? t.getToPlayer().getId() : null);
            row.put("delta", delta);
            row.put("method", t.getMethod() != null ? t.getMethod().toString() : "");
            row.put("notes", t.getNotes() != null ? t.getNotes() : "");
            row.put("createdBy", t.getCreatedByUsername() != null ? t.getCreatedByUsername() : "");
            rows.add(row);
        }
        java.util.Collections.reverse(rows); // newest first
        return ResponseEntity.ok(Map.of("rows", rows, "total", currentBank));
    }

    // Signed delta this row contributes to the tracked bank total. Adjustment rows already
    // store the signed delta directly as amount (see ImportSummaryController.setBankBalance).
    private java.math.BigDecimal bankDelta(PlayerTransfer t) {
        if (t.getMethod() == Transaction.Method.ADJUSTMENT) return t.getAmount();
        boolean toBank = t.getToBankAccount() != null || (t.getToPlayer() == null && t.getFromPlayer() != null);
        return toBank ? t.getAmount() : t.getAmount().negate();
    }
}
```

Also add the missing import at the top of the file (it currently imports `Transaction` already at line 3, so no new import is needed — verify with a quick grep before editing).

- [ ] **Step 2: Compile**

Run: `cd /c/projects/tracker && ./mvnw.cmd compile -q`
Expected: no output, exit code 0.

- [ ] **Step 3: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/controller/PlayerTransferController.java
git commit -m "Label manual bank adjustments distinctly in bank history endpoint"
```

---

### Task 4: Wire up the Club Wallets UI

**Files:**
- Modify: `c:\projects\poker-frontend\src\api.js:126`
- Modify: `c:\projects\poker-frontend\src\pages\ClubWallets.jsx`

- [ ] **Step 1: Update the `setBankBalance` API helper to accept notes**

In `c:\projects\poker-frontend\src\api.js`, replace line 126:
```js
export const setBankBalance = (bankBalance) => api.patch('/import-summary/bank-balance', { bankBalance });
```
with:
```js
export const setBankBalance = (bankBalance, notes) => api.patch('/import-summary/bank-balance', { bankBalance, notes });
```

- [ ] **Step 2: Import `setBankBalance` in ClubWallets.jsx**

In `c:\projects\poker-frontend\src\pages\ClubWallets.jsx`, line 2, replace:
```js
import { getWalletSummary, getWalletHistory, getBankAccounts, setAdminStartingBalance, getBankHistory } from '../api';
```
with:
```js
import { getWalletSummary, getWalletHistory, getBankAccounts, setAdminStartingBalance, getBankHistory, setBankBalance } from '../api';
```

- [ ] **Step 3: Add state and handler for the bank balance form**

Near the existing `startingBalanceSaving` state declaration (around line 13), add:
```js
  const [bankBalanceForm, setBankBalanceForm] = useState(null);
  const [bankBalanceSaving, setBankBalanceSaving] = useState(false);
```

Near the existing `handleSetStartingBalance` function (around line 188, right after its closing brace), add:
```js
  const handleUpdateBankBalance = async () => {
    const newBalance = parseFloat(bankBalanceForm?.amount);
    if (isNaN(newBalance) || newBalance < 0) {
      setMsg({ type: 'error', text: 'Enter a valid, non-negative balance' });
      return;
    }
    setBankBalanceSaving(true);
    try {
      const res = await setBankBalance(newBalance, bankBalanceForm?.notes || null);
      setMsg({ type: 'success', text: `Bank balance updated: ${fmt(res.data.previousBalance)} → ${fmt(res.data.newBalance)}` });
      setBankBalanceForm(null);
      load();
    } catch (e) {
      const errMsg = e?.response?.data?.error || 'Failed to update bank balance';
      setMsg({ type: 'error', text: errMsg });
    }
    setBankBalanceSaving(false);
  };
```

- [ ] **Step 4: Add the "Update Balance" control next to the Bank card**

Replace the existing bank wallets block (lines 282-290):
```js
            {bankWallets.map(b => (
              <tr key={b.id ?? 'bank'}>
                <td style={{ color: '#34d399', padding: '0.4rem 0' }}>
                  <span onClick={() => selectHolder(`BANK_${b.id}`)} style={{ cursor: 'pointer', textDecoration: 'underline', textDecorationColor: '#34d39988' }}
                    title="Click to view history">🏦 {b.name}</span>
                </td>
                <td style={{ textAlign: 'right', fontWeight: 600, color: '#34d399' }}>{fmt(b.balance)}</td>
              </tr>
            ))}
```
with:
```js
            {bankWallets.map(b => (
              <>
                <tr key={b.id ?? 'bank'}>
                  <td style={{ color: '#34d399', padding: '0.4rem 0' }}>
                    <span onClick={() => selectHolder(`BANK_${b.id}`)} style={{ cursor: 'pointer', textDecoration: 'underline', textDecorationColor: '#34d39988' }}
                      title="Click to view history">🏦 {b.name}</span>
                    {' '}
                    <button onClick={() => setBankBalanceForm(f => f ? null : { amount: b.balance ?? '', notes: '' })}
                      style={{ fontSize: '0.7rem', background: 'none', border: '1px solid #334155', color: '#64748b', borderRadius: '4px', padding: '1px 6px', cursor: 'pointer', marginLeft: '6px' }}>
                      {bankBalanceForm ? '✕' : '✏️ Update Balance'}
                    </button>
                  </td>
                  <td style={{ textAlign: 'right', fontWeight: 600, color: '#34d399' }}>{fmt(b.balance)}</td>
                </tr>
                {bankBalanceForm && (
                  <tr key={`${b.id ?? 'bank'}-balance-form`}>
                    <td colSpan={2} style={{ background: '#12151f', padding: '0.75rem 1rem' }}>
                      <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
                        <div>
                          <label style={{ fontSize: '0.72rem', color: '#94a3b8', display: 'block', marginBottom: '2px' }}>New Balance (₪)</label>
                          <input type="number" step="0.01" placeholder="0" value={bankBalanceForm.amount ?? ''}
                            onChange={e => setBankBalanceForm(f => ({ ...f, amount: e.target.value }))}
                            style={{ background: '#1a1d2e', border: '1px solid #34d39955', color: '#e2e8f0', padding: '5px 8px', borderRadius: '5px', width: '140px' }} />
                        </div>
                        <div>
                          <label style={{ fontSize: '0.72rem', color: '#94a3b8', display: 'block', marginBottom: '2px' }}>Note (optional)</label>
                          <input type="text" placeholder="e.g. reconciled with bank statement" value={bankBalanceForm.notes ?? ''}
                            onChange={e => setBankBalanceForm(f => ({ ...f, notes: e.target.value }))}
                            style={{ background: '#1a1d2e', border: '1px solid #2d3148', color: '#e2e8f0', padding: '5px 8px', borderRadius: '5px', width: '240px' }} />
                        </div>
                        <button onClick={handleUpdateBankBalance} disabled={bankBalanceSaving}
                          style={{ padding: '5px 12px', borderRadius: '5px', background: '#0f2a1a', border: 'none', color: '#34d399', cursor: 'pointer', fontWeight: 600, fontSize: '0.82rem' }}>
                          {bankBalanceSaving ? '...' : 'Save'}
                        </button>
                      </div>
                    </td>
                  </tr>
                )}
              </>
            ))}
```

- [ ] **Step 5: Label adjustment rows distinctly in the history table badge**

In `c:\projects\poker-frontend\src\pages\ClubWallets.jsx`, find the badge rendering line (around line 370):
```js
                          {h._synthetic ? 'OPENING' : isBankRow ? (h.type === 'XLS' ? 'XLS BASE' : 'BANK') : (h.type || 'Transfer')}
```
Replace with:
```js
                          {h._synthetic ? 'OPENING' : isBankRow ? (h.type === 'XLS' ? 'XLS BASE' : h.type === 'ADJUSTMENT' ? 'CORRECTION' : 'BANK') : (h.type || 'Transfer')}
```

- [ ] **Step 6: Lint check**

Run: `cd /c/projects/poker-frontend && npx eslint src/pages/ClubWallets.jsx src/api.js`
Expected: only the two pre-existing unrelated errors already known from earlier in this session (`Transfers.jsx` had `handleExpenseSubmit`/`idx` warnings — confirm no *new* errors are introduced in `ClubWallets.jsx` or `api.js`).

- [ ] **Step 7: Commit**

```bash
cd /c/projects/poker-frontend
git add src/api.js src/pages/ClubWallets.jsx
git commit -m "Add manual bank balance correction UI to Club Wallets"
```

---

### Task 5: Manual end-to-end verification on local servers

Both local servers are already running (backend on `:8080` via `mvnw spring-boot:run` with devtools auto-restart, frontend on `:5173` via `npm run dev`) — no need to restart them, devtools picks up the Task 1-3 backend changes automatically on compile.

- [ ] **Step 1: Recompile backend if not already picked up**

Run: `cd /c/projects/tracker && ./mvnw.cmd compile -q`
Expected: no output; watch the backend's background output for a "Restarting due to class path changes" log line confirming devtools reloaded.

- [ ] **Step 2: Open Club Wallets in the browser**

Navigate to `http://localhost:5173` → Club Wallets page. Confirm the "✏️ Update Balance" button now appears next to the "🏦 Bank" row.

- [ ] **Step 3: Perform a correction**

Click "Update Balance", enter a new balance different from the current one, optionally add a note, click Save. Confirm:
- A success message shows the previous → new balance.
- The Bank card's balance updates to the new value immediately.

- [ ] **Step 4: Verify it appears in history**

Click the "🏦 Bank" holder link (or select it in the Holder filter) to open its history. Confirm a new row appears tagged **CORRECTION** (not "BANK"), with the note text visible in the Notes column, and the "By" column showing the logged-in username.

- [ ] **Step 5: Verify reconciliation still holds**

Confirm the "XLS BASE" row (if present) plus the sum of all TRANSFER and CORRECTION deltas still add up to the current Bank total shown at the top (the History table's own footer total should match the Bank card balance).

- [ ] **Step 6: Try an invalid input**

Attempt to save a negative balance (e.g. `-100`). Confirm it's rejected with an error message and no change to the Bank balance.

---

### Task 6: Report completion

- [ ] Summarize to the user: both repos have new local commits (list them), local testing confirms the correction flow works end-to-end, and ask whether to push/deploy to production (per this project's deployment policy — always ask before pushing).
