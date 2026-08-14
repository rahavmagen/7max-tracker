# Grow (Meshulam) Deposit Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Grow (Meshulam) as a second player deposit method alongside KashCash — redirect-based checkout (card/Bit/bank transfer), webhook-confirmed chip crediting, admin reconciliation page — and relabel the now-multi-provider "KashCash" UI to generic "Deposit".

**Architecture:** Mirrors the existing KashCash integration's shape almost exactly: a `GrowInitiated` tracking row created on deposit-initiation, a public webhook as the sole source of truth for crediting (atomic idempotent claim, same `claimForProcessing` pattern), and a `Transaction(GROW_DEPOSIT)` row the existing admin "mark chips added" flow already knows how to handle. The one real difference: Grow's hosted checkout is reached via a full-page redirect (not an iframe + `postMessage`, which bank transfer and most bank-side 3-D-Secure/Bit flows can't reliably complete inside), so there is no client-driven `/finalize` call — the webhook is the only trigger.

**Tech Stack:** Spring Boot 3.5.11 / Java 21 / JPA / PostgreSQL backend (`c:/projects/tracker`); React 19 / Vite frontend, no TypeScript (`c:/projects/poker-frontend`). Tests: JUnit 5 + Mockito + AssertJ (already in `spring-boot-starter-test`, used today in `WalletServiceTest.java`).

Reference spec: `docs/superpowers/specs/2026-08-14-grow-meshulam-deposit-design.md`.

No live Grow account exists yet — response field names below are the best-documented guess (mirrors `KashcashService`'s `// NOTE: adjust ... to match actual response field name` precedent) and get corrected against real sandbox output per the design spec's setup checklist, without changing the surrounding architecture.

---

### Task 1: `Transaction` enum values

**Files:**
- Modify: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/entity/Transaction.java:53-59`

- [ ] **Step 1: Add `GROW_DEPOSIT` and `GROW` to the enums**

Current (lines 53–59):
```java
    public enum Type {
        DEPOSIT, WITHDRAWAL, CREDIT, PAYMENT, WHEEL_EXPENSE, CHIP_PROMO, PROMOTION, EXPENSE_REPAYMENT, TICKET_GRANT, KASHCASH_DEPOSIT, PLAYER_GIFT
    }

    public enum Method {
        BIT, PAYBOX, KASHCASH, CASH, BANK_TRANSFER, OTHER, ADJUSTMENT
    }
```

New:
```java
    public enum Type {
        DEPOSIT, WITHDRAWAL, CREDIT, PAYMENT, WHEEL_EXPENSE, CHIP_PROMO, PROMOTION, EXPENSE_REPAYMENT, TICKET_GRANT, KASHCASH_DEPOSIT, PLAYER_GIFT, GROW_DEPOSIT
    }

    public enum Method {
        BIT, PAYBOX, KASHCASH, CASH, BANK_TRANSFER, OTHER, ADJUSTMENT, GROW
    }
```

- [ ] **Step 2: Compile**

Run: `cd /c/projects/tracker && ./mvnw -q compile`
Expected: no output, exit code 0 (`@Enumerated(EnumType.STRING)` means no schema migration is needed for new enum values)

- [ ] **Step 3: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/entity/Transaction.java
git commit -m "Add GROW_DEPOSIT/GROW to Transaction enums for the Grow deposit integration"
```

---

### Task 2: `GrowInitiated` entity + repository

**Files:**
- Create: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/entity/GrowInitiated.java`
- Create: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/repository/GrowInitiatedRepository.java`

- [ ] **Step 1: Create the entity**

```java
package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "grow_initiated")
@Data
public class GrowInitiated {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String growProcessId;

    @Column(nullable = false)
    private Long playerId;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    private Boolean processed = false;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

- [ ] **Step 2: Create the repository**

```java
package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.GrowInitiated;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface GrowInitiatedRepository extends JpaRepository<GrowInitiated, Long> {
    Optional<GrowInitiated> findByGrowProcessId(String id);

    /**
     * Atomically claim this deposit for processing: sets processed=true only if it wasn't already.
     * Returns 1 if this caller won the claim, 0 if a retried/duplicate webhook already did.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GrowInitiated g SET g.processed = true WHERE g.id = :id AND (g.processed = false OR g.processed IS NULL)")
    int claimForProcessing(@Param("id") Long id);
}
```

- [ ] **Step 3: Compile**

Run: `cd /c/projects/tracker && ./mvnw -q compile`
Expected: no output, exit code 0. `spring.jpa.hibernate.ddl-auto=update` will create the `grow_initiated` table automatically on next app start — no manual migration needed (same as `kashcash_initiated` was created).

- [ ] **Step 4: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/entity/GrowInitiated.java src/main/java/com/sevenmax/tracker/repository/GrowInitiatedRepository.java
git commit -m "Add GrowInitiated entity + repository for tracking Grow deposit attempts"
```

---

### Task 3: `GrowService` — initiate deposit + response parsing (with tests)

**Files:**
- Create: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/GrowService.java`
- Create: `c:/projects/tracker/src/test/java/com/sevenmax/tracker/service/GrowServiceTest.java`

This task covers the parts of `GrowService` that don't require a live HTTP call: the request-body construction is exercised indirectly through `initiateDeposit`'s side effects (the saved `GrowInitiated` row), and the *response-parsing* logic is extracted into small pure static methods so it's directly unit-testable without mocking `HttpClient` — mirroring how `KashcashService.doInitiateWithRetry` falls back across multiple possible field names for `transactionId`, except here it's isolated into testable helpers instead of inlined.

- [ ] **Step 1: Write the failing tests for response parsing**

```java
package com.sevenmax.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrowServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void extractHostedUrl_readsNestedDataUrl() throws Exception {
        JsonNode j = json("{\"status\":1,\"data\":{\"url\":\"https://sandbox.meshulam.co.il/purchase/abc\"}}");
        assertThat(GrowService.extractHostedUrl(j)).isEqualTo("https://sandbox.meshulam.co.il/purchase/abc");
    }

    @Test
    void extractHostedUrl_fallsBackToTopLevelUrl() throws Exception {
        JsonNode j = json("{\"status\":1,\"url\":\"https://sandbox.meshulam.co.il/purchase/xyz\"}");
        assertThat(GrowService.extractHostedUrl(j)).isEqualTo("https://sandbox.meshulam.co.il/purchase/xyz");
    }

    @Test
    void extractProcessId_readsNestedDataProcessId() throws Exception {
        JsonNode j = json("{\"status\":1,\"data\":{\"processId\":\"p-123\",\"url\":\"https://x\"}}");
        assertThat(GrowService.extractProcessId(j)).isEqualTo("p-123");
    }

    @Test
    void extractProcessId_fallsBackToProcessToken() throws Exception {
        JsonNode j = json("{\"status\":1,\"data\":{\"processToken\":\"tok-456\",\"url\":\"https://x\"}}");
        assertThat(GrowService.extractProcessId(j)).isEqualTo("tok-456");
    }

    @Test
    void extractProcessId_returnsNullWhenMissing() throws Exception {
        JsonNode j = json("{\"status\":1,\"data\":{\"url\":\"https://x\"}}");
        assertThat(GrowService.extractProcessId(j)).isNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /c/projects/tracker && ./mvnw -q test -Dtest=GrowServiceTest`
Expected: FAIL — compile error, `GrowService` does not exist yet

- [ ] **Step 3: Write `GrowService` (initiate + parsing helpers)**

```java
package com.sevenmax.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sevenmax.tracker.entity.GrowInitiated;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.GrowInitiatedRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrowService {

    @Value("${grow.base-url}")
    private String baseUrl;

    @Value("${grow.user-id}")
    private String userId;

    @Value("${grow.page-code}")
    private String pageCode;

    @Value("${app.grow.callback-url}")
    private String notifyUrl;

    @Value("${app.grow.success-url}")
    private String successUrl;

    @Value("${app.grow.cancel-url}")
    private String cancelUrl;

    @Value("${app.kashcash.notification-emails:}")
    private String notificationEmails;

    @Value("${app.kashcash.notification-whatsapp:}")
    private String notificationWhatsApp;

    @Value("${resend.api-key:}")
    private String resendApiKey;

    @Value("${resend.from-email:noreply@7max.club}")
    private String fromEmail;

    private final GrowInitiatedRepository growInitiatedRepository;
    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final WhatsAppService whatsAppService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ── Initiate deposit ─────────────────────────────────────────────────────

    public Map<String, String> initiateDeposit(Long playerId, BigDecimal amount) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Player not found: " + playerId));
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("userId", userId);
            body.put("pageCode", pageCode);
            body.put("sum", amount);
            body.put("successUrl", successUrl);
            body.put("cancelUrl", cancelUrl);
            body.put("notifyUrl", notifyUrl);
            body.put("description", "7MAX deposit");
            Map<String, Object> pageField = new HashMap<>();
            pageField.put("fullName", player.getFullName() != null ? player.getFullName() : player.getUsername());
            if (player.getPhone() != null) pageField.put("phone", player.getPhone());
            body.put("pageField", pageField);

            String bodyJson = MAPPER.writeValueAsString(body);
            log.info("Grow create REQUEST → POST {}/createPaymentProcess body={}", baseUrl, bodyJson);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/createPaymentProcess"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("Grow create RESPONSE ← HTTP {} body={}", resp.statusCode(), resp.body());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Grow createPaymentProcess failed HTTP " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode json = MAPPER.readTree(resp.body());
            String url = extractHostedUrl(json);
            String processId = extractProcessId(json);
            if (url == null || url.isBlank() || processId == null || processId.isBlank()) {
                throw new RuntimeException("Grow createPaymentProcess: could not parse url/processId from response: " + resp.body());
            }

            GrowInitiated initiated = new GrowInitiated();
            initiated.setGrowProcessId(processId);
            initiated.setPlayerId(player.getId());
            initiated.setAmount(amount);
            growInitiatedRepository.save(initiated);

            log.info("Grow deposit initiated: player={}, amount={}, processId={}", player.getUsername(), amount, processId);
            return Map.of("url", url);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Grow initiate failed", e);
        }
    }

    // NOTE: field names below are the best-documented guess (Grow's docs describe a nested
    // "data" object but don't confirm exact key names) — confirm and adjust against a real
    // sandbox response before go-live, same as KashcashService's login-token field once was.

    static String extractHostedUrl(JsonNode json) {
        JsonNode data = json.get("data");
        if (data != null && data.has("url") && !data.get("url").asText().isBlank()) return data.get("url").asText();
        if (json.has("url") && !json.get("url").asText().isBlank()) return json.get("url").asText();
        return null;
    }

    static String extractProcessId(JsonNode json) {
        JsonNode data = json.get("data");
        if (data != null) {
            if (data.has("processId") && !data.get("processId").asText().isBlank()) return data.get("processId").asText();
            if (data.has("processToken") && !data.get("processToken").asText().isBlank()) return data.get("processToken").asText();
        }
        if (json.has("processId") && !json.get("processId").asText().isBlank()) return json.get("processId").asText();
        return null;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /c/projects/tracker && ./mvnw -q test -Dtest=GrowServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Compile the whole project**

Run: `cd /c/projects/tracker && ./mvnw -q compile`
Expected: no output, exit code 0

- [ ] **Step 6: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/service/GrowService.java src/test/java/com/sevenmax/tracker/service/GrowServiceTest.java
git commit -m "Add GrowService.initiateDeposit + tested response-parsing helpers"
```

---

### Task 4: `GrowService` — webhook handling + idempotent crediting (with tests)

**Files:**
- Modify: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/GrowService.java`
- Modify: `c:/projects/tracker/src/test/java/com/sevenmax/tracker/service/GrowServiceTest.java`

This is the highest-value part to test: it's the logic that decides whether chips get credited, and it must never double-credit. Mirrors `WalletServiceTest`'s style — mock the repositories, construct the service directly, assert on interactions.

- [ ] **Step 1: Write the failing webhook tests**

Add to `GrowServiceTest.java` (new imports + new test class body — the file becomes a `@ExtendWith(MockitoExtension.class)` class since these tests need mocks, while the pure-parsing tests above stay as plain `@Test` methods in the same file):

```java
package com.sevenmax.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sevenmax.tracker.entity.GrowInitiated;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.GrowInitiatedRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GrowServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock GrowInitiatedRepository growInitiatedRepository;
    @Mock PlayerRepository playerRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock TransactionService transactionService;
    @Mock WhatsAppService whatsAppService;

    GrowService growService;

    @BeforeEach
    void setUp() {
        growService = new GrowService(
                growInitiatedRepository, playerRepository, transactionRepository,
                transactionService, whatsAppService
        );
    }

    private JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    // ── existing parsing tests stay here ──

    @Test
    void extractHostedUrl_readsNestedDataUrl() throws Exception {
        JsonNode j = json("{\"status\":1,\"data\":{\"url\":\"https://sandbox.meshulam.co.il/purchase/abc\"}}");
        assertThat(GrowService.extractHostedUrl(j)).isEqualTo("https://sandbox.meshulam.co.il/purchase/abc");
    }

    @Test
    void extractHostedUrl_fallsBackToTopLevelUrl() throws Exception {
        JsonNode j = json("{\"status\":1,\"url\":\"https://sandbox.meshulam.co.il/purchase/xyz\"}");
        assertThat(GrowService.extractHostedUrl(j)).isEqualTo("https://sandbox.meshulam.co.il/purchase/xyz");
    }

    @Test
    void extractProcessId_readsNestedDataProcessId() throws Exception {
        JsonNode j = json("{\"status\":1,\"data\":{\"processId\":\"p-123\",\"url\":\"https://x\"}}");
        assertThat(GrowService.extractProcessId(j)).isEqualTo("p-123");
    }

    @Test
    void extractProcessId_fallsBackToProcessToken() throws Exception {
        JsonNode j = json("{\"status\":1,\"data\":{\"processToken\":\"tok-456\",\"url\":\"https://x\"}}");
        assertThat(GrowService.extractProcessId(j)).isEqualTo("tok-456");
    }

    @Test
    void extractProcessId_returnsNullWhenMissing() throws Exception {
        JsonNode j = json("{\"status\":1,\"data\":{\"url\":\"https://x\"}}");
        assertThat(GrowService.extractProcessId(j)).isNull();
    }

    // ── webhook tests ──

    @Test
    void handleWebhook_unknownProcessId_doesNotCreateTransaction() {
        when(growInitiatedRepository.findByGrowProcessId("missing")).thenReturn(Optional.empty());

        growService.handleWebhook(Map.of("status", "1", "processId", "missing"));

        verify(transactionService, never()).addTransaction(any());
    }

    @Test
    void handleWebhook_validUnclaimedProcessId_createsGrowDepositTransaction() {
        GrowInitiated initiated = new GrowInitiated();
        initiated.setId(1L);
        initiated.setGrowProcessId("p-123");
        initiated.setPlayerId(7L);
        initiated.setAmount(new BigDecimal("250.00"));

        Player player = new Player();
        player.setId(7L);
        player.setUsername("testplayer");

        when(growInitiatedRepository.findByGrowProcessId("p-123")).thenReturn(Optional.of(initiated));
        when(growInitiatedRepository.claimForProcessing(1L)).thenReturn(1);
        when(playerRepository.findById(7L)).thenReturn(Optional.of(player));

        growService.handleWebhook(Map.of("status", "1", "processId", "p-123"));

        verify(transactionService).addTransaction(argThat(tx ->
                tx.getType() == Transaction.Type.GROW_DEPOSIT
                        && tx.getMethod() == Transaction.Method.GROW
                        && tx.getAmount().compareTo(new BigDecimal("250.00")) == 0
                        && Boolean.FALSE.equals(tx.getChipsConfirmed())
                        && "p-123".equals(tx.getNotes())
        ));
    }

    @Test
    void handleWebhook_alreadyClaimed_doesNotCreateSecondTransaction() {
        GrowInitiated initiated = new GrowInitiated();
        initiated.setId(1L);
        initiated.setGrowProcessId("p-123");
        initiated.setPlayerId(7L);
        initiated.setAmount(new BigDecimal("250.00"));

        when(growInitiatedRepository.findByGrowProcessId("p-123")).thenReturn(Optional.of(initiated));
        when(growInitiatedRepository.claimForProcessing(1L)).thenReturn(0); // another webhook already won the claim

        growService.handleWebhook(Map.of("status", "1", "processId", "p-123"));

        verify(transactionService, never()).addTransaction(any());
    }

    @Test
    void handleWebhook_notApprovedStatus_ignored() {
        growService.handleWebhook(Map.of("status", "0", "processId", "p-123"));

        verifyNoInteractions(growInitiatedRepository, transactionService);
    }
}
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `cd /c/projects/tracker && ./mvnw -q test -Dtest=GrowServiceTest`
Expected: FAIL — `handleWebhook` does not exist on `GrowService` yet

- [ ] **Step 3: Add webhook handling to `GrowService`**

Add to `GrowService.java`, after `initiateDeposit`'s closing brace and before the `extractHostedUrl` parsing helpers:

```java
    // ── Webhook ───────────────────────────────────────────────────────────────

    @Transactional
    public void handleWebhook(Map<String, Object> payload) {
        log.info("Grow webhook RECEIVED payload={}", payload);
        if (!isApproved(payload)) {
            log.info("Grow webhook: not approved, ignoring. payload={}", payload);
            return;
        }
        String processId = firstNonBlank(payload.get("processId"), payload.get("processToken"), payload.get("transactionId"));
        if (processId == null) {
            log.warn("Grow webhook: missing processId/processToken/transactionId");
            return;
        }

        GrowInitiated initiated = growInitiatedRepository.findByGrowProcessId(processId).orElse(null);
        if (initiated == null) {
            log.warn("Grow webhook: unknown processId={}", processId);
            return;
        }
        if (growInitiatedRepository.claimForProcessing(initiated.getId()) == 0) {
            log.info("Grow webhook: already processed/claimed processId={}", processId);
            return;
        }

        Player player = playerRepository.findById(initiated.getPlayerId())
                .orElseThrow(() -> new RuntimeException("Player not found: " + initiated.getPlayerId()));

        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.GROW_DEPOSIT);
        tx.setAmount(initiated.getAmount());
        tx.setMethod(Transaction.Method.GROW);
        tx.setNotes(processId);
        tx.setChipsConfirmed(false);
        tx.setTransactionDate(LocalDate.now());
        transactionService.addTransaction(tx);

        sendDepositEmail(player, initiated.getAmount(), processId);
        sendDepositWhatsApp(player, initiated.getAmount());
        log.info("Grow deposit processed: player={}, amount={}, processId={}", player.getUsername(), initiated.getAmount(), processId);
    }

    // NOTE: exact status/id field names in Grow's real notifyUrl payload aren't confirmed yet
    // (their public docs describe createPaymentProcess but not the webhook body) — this checks
    // the most likely candidates and should be tightened once a real payload is seen.
    private boolean isApproved(Map<String, Object> payload) {
        Object status = payload.get("status");
        if (status == null) return false;
        String s = status.toString().toLowerCase();
        return s.equals("1") || s.equals("approved") || s.equals("success") || s.equals("true");
    }

    private String firstNonBlank(Object... values) {
        for (Object v : values) {
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }

    private void sendDepositWhatsApp(Player player, BigDecimal amount) {
        if (notificationWhatsApp == null || notificationWhatsApp.isBlank()) return;
        try {
            List<String> numbers = Arrays.stream(notificationWhatsApp.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (numbers.isEmpty()) return;
            String msg = String.format("💰 New Grow deposit: ₪%s from %s. Check the site.",
                    amount.toPlainString(), player != null ? player.getUsername() : "TEST");
            List<String> failed = whatsAppService.sendToAll(numbers, msg);
            if (!failed.isEmpty()) log.warn("Grow deposit WhatsApp failed for: {}", failed);
        } catch (Exception e) {
            log.error("Failed to send Grow deposit WhatsApp: {}", e.getMessage());
        }
    }

    private void sendDepositEmail(Player player, BigDecimal amount, String processId) {
        if (notificationEmails == null || notificationEmails.isBlank()) return;
        if (resendApiKey == null || resendApiKey.isBlank()) {
            log.warn("Resend API key not configured, skipping Grow deposit email");
            return;
        }
        try {
            List<String> recipients = Arrays.stream(notificationEmails.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (recipients.isEmpty()) return;

            String subject = String.format("New Grow deposit pending: \u20aa%s from player %s",
                    amount.toPlainString(), player != null ? player.getUsername() : "TEST");

            Map<String, Object> body = new HashMap<>();
            body.put("from", fromEmail);
            body.put("to", recipients);
            body.put("subject", subject);
            body.put("text", "new grow deposit - check web site");

            String bodyJson = MAPPER.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + resendApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("Grow deposit email sent for player={}", player != null ? player.getUsername() : "TEST");
            } else {
                log.error("Resend API error HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Failed to send Grow deposit email: {}", e.getMessage());
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /c/projects/tracker && ./mvnw -q test -Dtest=GrowServiceTest`
Expected: PASS (9 tests)

- [ ] **Step 5: Compile the whole project**

Run: `cd /c/projects/tracker && ./mvnw -q compile`
Expected: no output, exit code 0

- [ ] **Step 6: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/service/GrowService.java src/test/java/com/sevenmax/tracker/service/GrowServiceTest.java
git commit -m "Add GrowService webhook handling with idempotent-claim crediting + tests"
```

---

### Task 5: `GrowService` — admin/player query methods

**Files:**
- Modify: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/GrowService.java`

No new tests here — this is a direct mirror of `KashcashService`'s already-verified `getPending`/`getHistory`/`confirmChips`/`getMyDeposits`/`toDto` methods, filtered to the Grow type/notes field instead. Same trust level as the original.

- [ ] **Step 1: Add the query methods**

Add at the end of `GrowService.java`, before the final closing brace:

```java
    // ── Admin queries ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> getPending() {
        return transactionRepository.findPendingGrowDeposits()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public Map<String, Object> getHistory(LocalDate from, LocalDate to) {
        List<Transaction> txs;
        if (from != null && to != null) {
            txs = transactionRepository.findGrowDepositsBetween(
                    from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        } else {
            txs = transactionRepository.findAllGrowDeposits();
        }
        List<Map<String, Object>> rows = txs.stream().map(this::toDto).collect(Collectors.toList());
        BigDecimal total = txs.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("rows", rows, "total", total);
    }

    @Transactional
    public void confirmChips(Long transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + transactionId));
        if (tx.getType() != Transaction.Type.GROW_DEPOSIT) {
            throw new IllegalArgumentException("Not a Grow deposit transaction");
        }
        tx.setChipsConfirmed(true);
        transactionRepository.save(tx);
    }

    public List<Map<String, Object>> getMyDeposits(Long playerId) {
        return transactionRepository.findGrowDepositsByPlayerId(playerId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    private Map<String, Object> toDto(Transaction tx) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", tx.getId());
        m.put("playerId", tx.getPlayer().getId());
        m.put("username", tx.getPlayer().getUsername());
        m.put("fullName", tx.getPlayer().getFullName());
        m.put("amount", tx.getAmount());
        m.put("growProcessId", tx.getNotes());
        m.put("chipsConfirmed", Boolean.TRUE.equals(tx.getChipsConfirmed()));
        m.put("date", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
        return m;
    }
```

- [ ] **Step 2: Add the matching `TransactionRepository` query methods**

The existing KashCash equivalents are at `c:/projects/tracker/src/main/java/com/sevenmax/tracker/repository/TransactionRepository.java:80-90` (`findPendingKashcashDeposits`, `findAllKashcashDeposits`, `findKashcashDepositsBetween`, `findKashcashDepositsByPlayerId`) — note they use the fully-qualified enum reference in JPQL (`com.sevenmax.tracker.entity.Transaction.Type.KASHCASH_DEPOSIT`), not a string literal; match that exactly. `@Param` and `LocalDateTime` are already imported in this file (lines 6, 8) — no new imports needed. Add directly below line 90:

```java
    @Query("SELECT t FROM Transaction t WHERE t.type = com.sevenmax.tracker.entity.Transaction.Type.GROW_DEPOSIT AND t.chipsConfirmed = false ORDER BY t.createdAt DESC")
    List<Transaction> findPendingGrowDeposits();

    @Query("SELECT t FROM Transaction t WHERE t.type = com.sevenmax.tracker.entity.Transaction.Type.GROW_DEPOSIT ORDER BY t.createdAt DESC")
    List<Transaction> findAllGrowDeposits();

    @Query("SELECT t FROM Transaction t WHERE t.type = com.sevenmax.tracker.entity.Transaction.Type.GROW_DEPOSIT AND t.createdAt >= :from AND t.createdAt < :to ORDER BY t.createdAt DESC")
    List<Transaction> findGrowDepositsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT t FROM Transaction t WHERE t.type = com.sevenmax.tracker.entity.Transaction.Type.GROW_DEPOSIT AND t.player.id = :playerId ORDER BY t.createdAt DESC")
    List<Transaction> findGrowDepositsByPlayerId(@Param("playerId") Long playerId);
```

- [ ] **Step 3: Compile**

Run: `cd /c/projects/tracker && ./mvnw -q compile`
Expected: no output, exit code 0

- [ ] **Step 4: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/service/GrowService.java src/main/java/com/sevenmax/tracker/repository/TransactionRepository.java
git commit -m "Add Grow deposit admin/player query methods (mirrors KashCash)"
```

---

### Task 6: `GrowController`

**Files:**
- Create: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/controller/GrowController.java`

- [ ] **Step 1: Create the controller**

```java
package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.service.GrowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/grow")
@RequiredArgsConstructor
public class GrowController {

    private final GrowService growService;
    private final UserRepository userRepository;

    /** PLAYER: initiate a Grow deposit — returns the hosted checkout URL to redirect to. */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiate(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Object rawAmount = body.get("amount");
            if (rawAmount == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing required field: amount"));
            }
            BigDecimal amount = new BigDecimal(rawAmount.toString());
            if (amount.compareTo(BigDecimal.ONE) < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Minimum deposit is 1"));
            }
            User user = userRepository.findByUsername(auth.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            if (user.getPlayer() == null) {
                return ResponseEntity.status(403).body(Map.of("error", "No player linked to this account"));
            }
            Map<String, String> result = growService.initiateDeposit(user.getPlayer().getId(), amount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Grow initiate error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PUBLIC: Grow's notifyUrl webhook — always returns 200 so Grow does not retry indefinitely */
    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestBody Map<String, Object> payload) {
        try {
            growService.handleWebhook(payload);
        } catch (Exception e) {
            log.error("Grow webhook error: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok(Map.of("received", true));
    }

    /** ADMIN/MANAGER: list deposits where chipsConfirmed=false */
    @GetMapping("/pending")
    public ResponseEntity<?> getPending(Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(growService.getPending());
    }

    /** ADMIN/MANAGER: full history with optional ?from=yyyy-MM-dd&to=yyyy-MM-dd */
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        try {
            LocalDate fromDate = from != null ? LocalDate.parse(from) : null;
            LocalDate toDate = to != null ? LocalDate.parse(to) : null;
            return ResponseEntity.ok(growService.getHistory(fromDate, toDate));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** ADMIN/MANAGER: mark chips as added for a deposit transaction */
    @PostMapping("/confirm/{id}")
    public ResponseEntity<?> confirmChips(@PathVariable Long id, Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        try {
            growService.confirmChips(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PLAYER: own Grow deposit history */
    @GetMapping("/my")
    public ResponseEntity<?> myDeposits(Authentication auth) {
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null || user.getPlayer() == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(growService.getMyDeposits(user.getPlayer().getId()));
    }

    private boolean isAdminOrManager(Authentication auth) {
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        return user != null && (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MANAGER);
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd /c/projects/tracker && ./mvnw -q compile`
Expected: no output, exit code 0

- [ ] **Step 3: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/controller/GrowController.java
git commit -m "Add GrowController REST endpoints"
```

---

### Task 7: Permit the webhook path in Spring Security

**Files:**
- Modify: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/security/SecurityConfig.java:33`

- [ ] **Step 1: Add `/api/grow/webhook` to the public matcher list**

Current (line 33):
```java
                .requestMatchers("/api/auth/login", "/api/auth/change-password", "/api/reports/upload-auto", "/api/kashcash/webhook").permitAll()
```

New:
```java
                .requestMatchers("/api/auth/login", "/api/auth/change-password", "/api/reports/upload-auto", "/api/kashcash/webhook", "/api/grow/webhook").permitAll()
```

- [ ] **Step 2: Compile**

Run: `cd /c/projects/tracker && ./mvnw -q compile`
Expected: no output, exit code 0

- [ ] **Step 3: Commit**

```bash
cd /c/projects/tracker
git add src/main/java/com/sevenmax/tracker/security/SecurityConfig.java
git commit -m "Permit the Grow webhook endpoint without JWT auth"
```

---

### Task 8: Configuration placeholders

**Files:**
- Modify: `c:/projects/tracker/src/main/resources/application.properties`
- Modify: `c:/projects/tracker/src/main/resources/application-local.properties`

- [ ] **Step 1: Add Grow config to `application.properties`**

The existing KashCash block is at `c:/projects/tracker/src/main/resources/application.properties:26-33`:
```properties
kashcash.base-url=${KASHCASH_BASE_URL:https://prod-api.safecashapps.com/api/external/v1}
kashcash.username=${KASHCASH_USERNAME:}
kashcash.password=${KASHCASH_PASSWORD:}
kashcash.business-id=${KASHCASH_BUSINESS_ID:}
kashcash.pos-vendor-id=${KASHCASH_POS_VENDOR_ID:}
app.kashcash.callback-url=${KASHCASH_CALLBACK_URL:https://your-prod-domain/api/kashcash/webhook}
app.kashcash.notification-emails=${NOTIFY_EMAILS:}
app.kashcash.notification-whatsapp=${NOTIFY_WHATSAPP:0526194444,0505666501,0543474774}
```
Note its callback-url default is a placeholder (`your-prod-domain`) — the real value is set via the `KASHCASH_CALLBACK_URL` Railway env var directly, never relied on from this default. Match that same approach for Grow. Append directly below line 33:

```properties

# Grow (Meshulam) deposit API - no account registered yet, placeholders until onboarding
grow.base-url=${GROW_BASE_URL:https://sandbox.meshulam.co.il/api/light/server/1.0}
grow.user-id=${GROW_USER_ID:}
grow.page-code=${GROW_PAGE_CODE:}
app.grow.callback-url=${GROW_CALLBACK_URL:https://your-prod-domain/api/grow/webhook}
app.grow.success-url=${GROW_SUCCESS_URL:https://your-prod-domain/deposit/grow/return}
app.grow.cancel-url=${GROW_CANCEL_URL:https://your-prod-domain/deposit}
```

`app.kashcash.notification-emails`/`notification-whatsapp` (lines 32-33) are reused as-is for Grow's `GrowService` — no separate Grow notification config needed, per the design spec.

- [ ] **Step 2: Point local dev at the sandbox with blank credentials (fine — `/initiate` will error until real credentials exist, everything else is unaffected)**

No changes needed in `application-local.properties` — the defaults in `application.properties` already point `grow.base-url` at the sandbox and leave `grow.user-id`/`grow.page-code` blank, which is correct until the account is registered per the design spec's setup checklist.

- [ ] **Step 3: Compile and boot-check locally**

Run: `cd /c/projects/tracker && ./mvnw -q compile`
Expected: no output, exit code 0

- [ ] **Step 4: Commit**

```bash
cd /c/projects/tracker
git add src/main/resources/application.properties
git commit -m "Add Grow deposit API configuration placeholders"
```

---

### Task 9: Frontend `api.js` — Grow endpoints

**Files:**
- Modify: `c:/projects/poker-frontend/src/api.js` (add directly below the existing `kashcash` block, e.g. after line 187 per the `getMyKashcashDeposits` export)

- [ ] **Step 1: Add the Grow API functions**

```js
export const initiateGrowDeposit = (amount) => api.post('/grow/initiate', { amount });
export const getPendingGrowDeposits = () => api.get('/grow/pending');
export const getGrowHistory = (from, to) => {
  const params = {};
  if (from) params.from = from;
  if (to) params.to = to;
  return api.get('/grow/history', { params });
};
export const confirmGrowDeposit = (id) => api.post(`/grow/confirm/${id}`);
export const getMyGrowDeposits = () => api.get('/grow/my');
```

(Match the exact `params` construction the neighboring `getKashcashHistory` uses at line 180 — reuse that shape rather than reinventing it.)

- [ ] **Step 2: Commit**

```bash
cd /c/projects/poker-frontend
git add src/api.js
git commit -m "Add Grow deposit API client functions"
```

---

### Task 10: Frontend `Deposit.jsx` — relabel to generic "Deposit", add the Grow button

**Files:**
- Modify: `c:/projects/poker-frontend/src/pages/Deposit.jsx`

The existing KashCash iframe/postMessage/polling logic (lines 1–124) is untouched. Only the header card's title/subtitle and button label are relabeled (KashCash is still one of the two options, just no longer the page's identity), and a second button is added below the KashCash button that redirects full-page to Grow's checkout.

- [ ] **Step 1: Add Grow state + handler**

In the component body, right after the existing `handlePay` function (after line 124), add:

```js
  const [growLoading, setGrowLoading] = useState(false);

  const handlePayGrow = async () => {
    const num = parseFloat(amount);
    if (!num || num < 1) return;
    setGrowLoading(true);
    try {
      const res = await initiateGrowDeposit(num);
      window.location.href = res.data.url; // full-page redirect - not an iframe, bank transfer/Bit can't reliably complete inside one
    } catch {
      setGrowLoading(false);
      updateStatus('error');
    }
  };
```

- [ ] **Step 2: Import `initiateGrowDeposit`**

Change line 2 from:
```js
import { initiateKashcashDeposit, finalizeKashcashDeposit, getMyKashcashDeposits } from '../api';
```
to:
```js
import { initiateKashcashDeposit, finalizeKashcashDeposit, getMyKashcashDeposits, initiateGrowDeposit } from '../api';
```

- [ ] **Step 3: Relabel the header (lines 156–159)**

Current:
```jsx
            <div>
              <div style={{ color: '#fff', fontWeight: 700, fontSize: '1.1rem' }}>KashCash Deposit</div>
              <div style={{ color: '#94a3b8', fontSize: '0.8rem', marginTop: 2 }}>Secure payment via KashCash</div>
            </div>
```
New:
```jsx
            <div>
              <div style={{ color: '#fff', fontWeight: 700, fontSize: '1.1rem' }}>Deposit</div>
              <div style={{ color: '#94a3b8', fontSize: '0.8rem', marginTop: 2 }}>Choose a payment method below</div>
            </div>
```

- [ ] **Step 4: Relabel the KashCash button text (line 230) and add the Grow button below it**

Current (line 213–231):
```jsx
            <button
              onClick={handlePay}
              disabled={loading || !amount || parseFloat(amount) < 1}
              style={{
                width: '100%',
                padding: '12px 24px',
                background: loading || !amount || parseFloat(amount) < 1 ? '#334155' : 'var(--accent)',
                color: loading || !amount || parseFloat(amount) < 1 ? '#64748b' : '#0f172a',
                border: 'none',
                borderRadius: 8,
                fontWeight: 700,
                fontSize: '1rem',
                cursor: loading || !amount || parseFloat(amount) < 1 ? 'not-allowed' : 'pointer',
                transition: 'background 0.15s',
                letterSpacing: '0.02em',
              }}
            >
              {loading ? 'Processing...' : `Deposit KashCash${amount && parseFloat(amount) >= 1 ? ` · ₪${parseFloat(amount).toLocaleString()}` : ''}`}
            </button>
```

New (relabeled + Grow button added directly below):
```jsx
            <button
              onClick={handlePay}
              disabled={loading || !amount || parseFloat(amount) < 1}
              style={{
                width: '100%',
                padding: '12px 24px',
                background: loading || !amount || parseFloat(amount) < 1 ? '#334155' : 'var(--accent)',
                color: loading || !amount || parseFloat(amount) < 1 ? '#64748b' : '#0f172a',
                border: 'none',
                borderRadius: 8,
                fontWeight: 700,
                fontSize: '1rem',
                cursor: loading || !amount || parseFloat(amount) < 1 ? 'not-allowed' : 'pointer',
                transition: 'background 0.15s',
                letterSpacing: '0.02em',
              }}
            >
              {loading ? 'Processing...' : `Pay with KashCash${amount && parseFloat(amount) >= 1 ? ` · ₪${parseFloat(amount).toLocaleString()}` : ''}`}
            </button>

            <button
              onClick={handlePayGrow}
              disabled={growLoading || !amount || parseFloat(amount) < 1}
              style={{
                width: '100%',
                marginTop: 10,
                padding: '12px 24px',
                background: 'transparent',
                color: growLoading || !amount || parseFloat(amount) < 1 ? '#64748b' : 'var(--text-primary)',
                border: '1px solid var(--border)',
                borderRadius: 8,
                fontWeight: 700,
                fontSize: '1rem',
                cursor: growLoading || !amount || parseFloat(amount) < 1 ? 'not-allowed' : 'pointer',
                letterSpacing: '0.02em',
              }}
            >
              {growLoading ? 'Redirecting…' : 'Pay with Grow (Card / Bit / Bank Transfer)'}
            </button>
```

- [ ] **Step 5: Compile-check via the dev server**

Run: `cd /c/projects/poker-frontend && npm run build`
Expected: build succeeds, no errors

- [ ] **Step 6: Commit**

```bash
cd /c/projects/poker-frontend
git add src/pages/Deposit.jsx
git commit -m "Relabel Deposit page to generic title, add Grow as a second payment option"
```

---

### Task 11: Frontend `GrowDepositReturn.jsx` — return/confirmation page

**Files:**
- Create: `c:/projects/poker-frontend/src/pages/GrowDepositReturn.jsx`
- Modify: `c:/projects/poker-frontend/src/App.jsx` (route registration — done in Task 13)

- [ ] **Step 1: Create the page**

```jsx
import { useState, useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { getMyGrowDeposits } from '../api';

export default function GrowDepositReturn() {
  const [status, setStatus] = useState('polling'); // 'polling' | 'found' | 'timeout'
  const [amount, setAmount] = useState(null);
  const startTimeRef = useRef(Date.now());
  const initialCountRef = useRef(null);

  useEffect(() => {
    const pollId = setInterval(async () => {
      try {
        const res = await getMyGrowDeposits();
        if (initialCountRef.current === null) {
          initialCountRef.current = res.data.length;
        } else if (res.data.length > initialCountRef.current) {
          clearInterval(pollId);
          setStatus('found');
          setAmount(res.data[0].amount);
          return;
        }
      } catch { /* ignore, keep polling */ }
      if (Date.now() - startTimeRef.current > 30000) {
        clearInterval(pollId);
        setStatus((s) => (s === 'polling' ? 'timeout' : s));
      }
    }, 3000);
    return () => clearInterval(pollId);
  }, []);

  return (
    <div style={{ maxWidth: 500, margin: '3rem auto', padding: '1.5rem', textAlign: 'center' }}>
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '2rem' }}>
        {status === 'polling' && (
          <>
            <div style={{ fontSize: '2rem', marginBottom: '1rem' }}>⏳</div>
            <div style={{ color: 'var(--text-primary)', fontWeight: 600, fontSize: '1.1rem', marginBottom: 8 }}>
              Processing your deposit…
            </div>
            <div style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
              We're confirming your payment with Grow. This usually takes a few seconds.
            </div>
          </>
        )}
        {status === 'found' && (
          <>
            <div style={{ fontSize: '2rem', marginBottom: '1rem' }}>✓</div>
            <div style={{ color: '#86efac', fontWeight: 700, fontSize: '1.1rem', marginBottom: 8 }}>
              Confirmed! {amount ? `₪${Number(amount).toLocaleString()} added` : 'Deposit received'}
            </div>
            <div style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
              Chips will be added to your account shortly.
            </div>
          </>
        )}
        {status === 'timeout' && (
          <>
            <div style={{ fontSize: '2rem', marginBottom: '1rem' }}>💬</div>
            <div style={{ color: 'var(--text-primary)', fontWeight: 600, fontSize: '1.1rem', marginBottom: 8 }}>
              We'll confirm your deposit shortly
            </div>
            <div style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
              Check your balance page in a few minutes, or contact support if it doesn't show up.
            </div>
          </>
        )}
        <Link to="/deposit" style={{ display: 'inline-block', marginTop: '1.5rem', color: 'var(--accent)', textDecoration: 'none', fontWeight: 600 }}>
          ← Back to Deposit
        </Link>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
cd /c/projects/poker-frontend
git add src/pages/GrowDepositReturn.jsx
git commit -m "Add Grow deposit return/confirmation page"
```

---

### Task 12: Add Grow sections to the existing `OpenRequests.jsx` admin page

**Files:**
- Modify: `c:/projects/poker-frontend/src/pages/OpenRequests.jsx`

**Important — corrects an assumption from the design spec:** `KashcashDeposits.jsx` (the standalone
page the spec referenced) is actually dead code — it exists on disk but is never imported or
routed in `App.jsx`. The real, live admin reconciliation UI for KashCash deposits is a pair of
sections (Pending + History) inside `OpenRequests.jsx`, reached via the "Open Requests" link in
the nav dropdown at `/open-requests`, alongside a third section for player join requests. This
task adds matching Grow sections to that same live page instead of creating a new unrouted one.

- [ ] **Step 1: Add Grow imports**

Change line 3-6 from:
```js
import {
  getPendingKashcashDeposits, getKashcashHistory, confirmKashcashDeposit,
  getPendingJoinRequests, getJoinHistory, approveJoinRequest, rejectJoinRequest,
} from '../api';
```
to:
```js
import {
  getPendingKashcashDeposits, getKashcashHistory, confirmKashcashDeposit,
  getPendingJoinRequests, getJoinHistory, approveJoinRequest, rejectJoinRequest,
  getPendingGrowDeposits, getGrowHistory, confirmGrowDeposit,
} from '../api';
```

- [ ] **Step 2: Add Grow state + loaders + confirm handler**

Add directly after the existing `handleConfirm` function (after line 53, before `handleJoinAction`):
```js
  const [growPending, setGrowPending] = useState([]);
  const [growHistory, setGrowHistory] = useState(null);
  const [growFrom, setGrowFrom] = useState('');
  const [growTo, setGrowTo] = useState('');
  const [growConfirming, setGrowConfirming] = useState(null);

  const loadGrowPending = () =>
    getPendingGrowDeposits().then(r => setGrowPending(r.data)).catch(() => {});
  const loadGrowHistory = () =>
    getGrowHistory(growFrom || null, growTo || null).then(r => setGrowHistory(r.data)).catch(() => {});

  const handleConfirmGrow = async (id) => {
    setGrowConfirming(id);
    setMsg(null);
    try {
      await confirmGrowDeposit(id);
      setMsg({ type: 'success', text: 'Marked as done — chips confirmed.' });
      await Promise.all([loadGrowPending(), loadGrowHistory()]);
    } catch {
      setMsg({ type: 'error', text: 'Failed to confirm. Please try again.' });
    }
    setGrowConfirming(null);
  };
```

- [ ] **Step 3: Load Grow data on mount**

Change the mount `useEffect` (lines 35-40) from:
```js
  useEffect(() => {
    loadPending();
    loadHistory();
    loadJoinPending();
    loadJoinHistory();
  }, []);
```
to:
```js
  useEffect(() => {
    loadPending();
    loadHistory();
    loadJoinPending();
    loadJoinHistory();
    loadGrowPending();
    loadGrowHistory();
  }, []);
```

- [ ] **Step 4: Add the two Grow sections after the existing "KashCash History" section**

Insert directly after the closing `</div>` of the "KashCash History" section (after line 296, before the component's final closing `</div>` at line 297):
```jsx
      {/* Grow Deposits (Open) — Pending */}
      <div style={card}>
        <h3 style={{ color: 'var(--text-primary)', marginTop: 0, marginBottom: '1rem' }}>
          Grow Deposits (Open)
          {growPending.length > 0 && (
            <span style={{ marginLeft: 10, background: '#dc2626', color: '#fff', borderRadius: 12, padding: '2px 10px', fontSize: '0.8rem', fontWeight: 600 }}>
              {growPending.length}
            </span>
          )}
        </h3>

        {growPending.length === 0 ? (
          <p style={{ color: 'var(--text-muted)', margin: 0 }}>No pending deposits</p>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Date', 'Username', 'Full Name', 'Amount', 'Grow Process ID', 'Action'].map(h => (
                    <th key={h} style={th}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {growPending.map(row => (
                  <tr key={row.id}>
                    <td style={td}>{row.date ? new Date(row.date).toLocaleString('he-IL') : '—'}</td>
                    <td style={td}><Link to={`/player/${row.playerId}`} style={{ color: 'var(--accent)', fontWeight: 500, textDecoration: 'none' }}>{row.username}</Link></td>
                    <td style={td}><Link to={`/player/${row.playerId}`} style={{ color: 'var(--text-secondary)', textDecoration: 'none' }}>{row.fullName}</Link></td>
                    <td style={{ ...td, fontWeight: 600, color: 'var(--accent)' }}>{fmt(row.amount)}</td>
                    <td style={{ ...td, fontSize: '0.75rem', fontFamily: 'monospace', color: 'var(--text-muted)' }}>{row.growProcessId}</td>
                    <td style={td}>
                      <button
                        onClick={() => handleConfirmGrow(row.id)}
                        disabled={growConfirming === row.id}
                        style={{
                          background: growConfirming === row.id ? '#334155' : '#16a34a',
                          color: '#fff', border: 'none', borderRadius: 6,
                          padding: '5px 14px', fontSize: '0.85rem', fontWeight: 600,
                          cursor: growConfirming === row.id ? 'not-allowed' : 'pointer',
                        }}
                      >
                        {growConfirming === row.id ? '...' : 'Mark Done'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Grow History */}
      <div style={card}>
        <h3 style={{ color: 'var(--text-primary)', marginTop: 0, marginBottom: '1rem' }}>Grow History</h3>
        <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          {[['From', growFrom, setGrowFrom], ['To', growTo, setGrowTo]].map(([label, val, setter]) => (
            <div key={label}>
              <label style={{ display: 'block', color: 'var(--text-muted)', fontSize: '0.8rem', marginBottom: 4 }}>{label}</label>
              <DateInput value={val} onChange={setter} />
            </div>
          ))}
          <button onClick={loadGrowHistory}
            style={{ background: 'var(--accent)', color: '#0f172a', border: 'none', borderRadius: 6, padding: '7px 20px', fontWeight: 600, cursor: 'pointer', fontSize: '0.9rem' }}>
            Filter
          </button>
          {(growFrom || growTo) && (
            <button onClick={() => { setGrowFrom(''); setGrowTo(''); getGrowHistory(null, null).then(r => setGrowHistory(r.data)).catch(() => {}); }}
              style={{ background: 'none', border: '1px solid var(--border)', color: 'var(--text-muted)', borderRadius: 6, padding: '7px 14px', cursor: 'pointer', fontSize: '0.85rem' }}>
              Clear
            </button>
          )}
        </div>
        {growHistory && (
          growHistory.rows.length === 0 ? (
            <p style={{ color: 'var(--text-muted)' }}>No deposits in this period</p>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['Date', 'Username', 'Full Name', 'Amount', 'Grow Process ID', 'Status'].map(h => (
                      <th key={h} style={th}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {growHistory.rows.map(row => (
                    <tr key={row.id}>
                      <td style={td}>{row.date ? new Date(row.date).toLocaleString('he-IL') : '—'}</td>
                      <td style={{ ...td, fontWeight: 500, color: 'var(--text-primary)' }}>{row.username}</td>
                      <td style={td}>{row.fullName}</td>
                      <td style={{ ...td, fontWeight: 600, color: 'var(--accent)' }}>{fmt(row.amount)}</td>
                      <td style={{ ...td, fontSize: '0.75rem', fontFamily: 'monospace', color: 'var(--text-muted)' }}>{row.growProcessId}</td>
                      <td style={td}>
                        {row.chipsConfirmed
                          ? <span style={{ color: '#86efac', fontWeight: 600 }}>Done</span>
                          : <span style={{ color: '#fbbf24' }}>Pending chips</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr style={{ borderTop: '2px solid var(--border)' }}>
                    <td colSpan={3} style={{ padding: '10px 12px', fontWeight: 700, color: 'var(--text-primary)' }}>Total</td>
                    <td style={{ padding: '10px 12px', fontWeight: 700, color: 'var(--accent)', fontSize: '1rem' }}>{fmt(growHistory.total)}</td>
                    <td colSpan={2} />
                  </tr>
                </tfoot>
              </table>
            </div>
          )
        )}
      </div>
```

- [ ] **Step 5: Build check**

Run: `cd /c/projects/poker-frontend && npm run build`
Expected: build succeeds, no errors

- [ ] **Step 6: Commit**

```bash
cd /c/projects/poker-frontend
git add src/pages/OpenRequests.jsx
git commit -m "Add Grow Deposits pending/history sections to the Open Requests admin page"
```

---

### Task 13: `App.jsx` — return-page route + "KashCash" → "Deposit" nav rename

**Files:**
- Modify: `c:/projects/poker-frontend/src/App.jsx`

No new nav link or route is needed for the admin Grow reconciliation UI — it lives inside the
existing "Open Requests" page/route (Task 12), already reachable via the existing
`<NavLink to="/open-requests">Open Requests</NavLink>` at line 201. This task only adds the
player-facing return-page route and relabels the two "KashCash" nav strings to "Deposit".

- [ ] **Step 1: Import `GrowDepositReturn`**

Near line 45 (`import Deposit from './pages/Deposit';`), add:
```js
import GrowDepositReturn from './pages/GrowDepositReturn';
```

- [ ] **Step 2: Rename the admin dropdown trigger label (line 187)**

Current:
```jsx
        KashCash ▾
```
New:
```jsx
        Deposit ▾
```

- [ ] **Step 3: Rename the admin dropdown's deposit link (line 200)**

Current:
```jsx
          <NavLink to="/deposit">Deposit KashCash</NavLink>
```
New:
```jsx
          <NavLink to="/deposit">Deposit</NavLink>
```

- [ ] **Step 4: Rename the player nav link (line 288)**

Current:
```jsx
              <NavLink to="/deposit">Deposit KashCash</NavLink>
```
New:
```jsx
              <NavLink to="/deposit">Deposit</NavLink>
```

- [ ] **Step 5: Add the return-page route to both role-gated route blocks**

There are two `<Route path="/deposit" element={<Deposit />} />` registrations — one in the admin
block (line 350) and one in the player block (line 363). Add the return route directly after each,
so it's reachable under both role gates exactly like `/deposit` already is:

Admin block, current (line 350):
```jsx
              <Route path="/deposit" element={<Deposit />} />
```
New:
```jsx
              <Route path="/deposit" element={<Deposit />} />
              <Route path="/deposit/grow/return" element={<GrowDepositReturn />} />
```

Player block, current (line 363):
```jsx
              <Route path="/deposit" element={<Deposit />} />
```
New:
```jsx
              <Route path="/deposit" element={<Deposit />} />
              <Route path="/deposit/grow/return" element={<GrowDepositReturn />} />
```

- [ ] **Step 6: Build check**

Run: `cd /c/projects/poker-frontend && npm run build`
Expected: build succeeds, no errors

- [ ] **Step 7: Commit**

```bash
cd /c/projects/poker-frontend
git add src/App.jsx
git commit -m "Wire up the Grow deposit return route, rename KashCash nav labels to Deposit"
```

---

### Task 14: Local end-to-end verification (no live Grow account required)

**Files:** none — verification only

- [ ] **Step 1: Run the full backend test suite**

Run: `cd /c/projects/tracker && ./mvnw -q test`
Expected: all tests pass, including the 9 new `GrowServiceTest` cases

- [ ] **Step 2: Boot the backend locally against the local DB**

Run (background): `cd /c/projects/tracker && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
Expected: `Started Application in N seconds`, no errors; confirm `grow_initiated` table was auto-created:
```bash
"/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d poker_tracker -c "\d grow_initiated"
```
Expected: table exists with columns `id, grow_process_id, player_id, amount, processed, created_at`

- [ ] **Step 3: POST a synthetic webhook payload to verify crediting end-to-end**

First insert a fake `GrowInitiated` row directly (standing in for a real `initiateDeposit` call, since there's no live Grow account to call yet):
```bash
"/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d poker_tracker -c \
  "INSERT INTO grow_initiated (grow_process_id, player_id, amount, processed, created_at) VALUES ('test-proc-1', 1, 50.00, false, now());"
```
(Replace `player_id` with a real id from local `SELECT id FROM players LIMIT 1;` if `1` doesn't exist.)

Then POST the webhook:
```bash
curl -s -X POST http://localhost:8080/api/grow/webhook \
  -H "Content-Type: application/json" \
  -d '{"status":"1","processId":"test-proc-1"}'
```
Expected: `{"received":true}`

Verify the Transaction was created and the row was claimed:
```bash
"/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d poker_tracker -c \
  "SELECT type, method, amount, chips_confirmed, notes FROM transactions WHERE notes = 'test-proc-1';"
"/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d poker_tracker -c \
  "SELECT processed FROM grow_initiated WHERE grow_process_id = 'test-proc-1';"
```
Expected: one `GROW_DEPOSIT` / `GROW` transaction with `amount=50.00`, `chips_confirmed=false`; `processed=true`

- [ ] **Step 4: POST the same webhook again to verify idempotency**

```bash
curl -s -X POST http://localhost:8080/api/grow/webhook \
  -H "Content-Type: application/json" \
  -d '{"status":"1","processId":"test-proc-1"}'
"/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d poker_tracker -c \
  "SELECT count(*) FROM transactions WHERE notes = 'test-proc-1';"
```
Expected: still exactly 1 transaction (not 2) — the duplicate webhook was a no-op

- [ ] **Step 5: Boot the frontend and click through the UI**

Run (background): `cd /c/projects/poker-frontend && npm run dev`
- Open `http://localhost:5173/deposit` as a player → confirm the header now says "Deposit" (not "KashCash Deposit"), confirm both "Pay with KashCash" and "Pay with Grow (Card / Bit / Bank Transfer)" buttons render, confirm the deposit history table shows the `test-proc-1` row credited above (it went to whichever player id was used in Step 3 — log in as that player, or check via `/open-requests` as admin instead)
- Open `http://localhost:5173/open-requests` as admin → confirm the new "Grow Deposits (Open)" and "Grow History" sections render below the existing KashCash ones, confirm the `test-proc-1` row shows in Grow History with the correct amount, click "Mark Done" on it if still pending → confirm it flips to "Done"
- Confirm the admin nav dropdown trigger now reads "Deposit ▾" (not "KashCash ▾")

Expected: no console errors, all of the above renders and behaves as described

- [ ] **Step 6: Clean up the synthetic test row (optional, local DB only)**

```bash
"/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d poker_tracker -c \
  "DELETE FROM transactions WHERE notes = 'test-proc-1'; DELETE FROM grow_initiated WHERE grow_process_id = 'test-proc-1';"
```

---

## Not in scope for this plan (see design spec's Setup Checklist)

- Registering the real Grow/Meshulam merchant account and obtaining `userId`/`pageCode`
- Confirming exact `createPaymentProcess` response and `notifyUrl` webhook field names against a real sandbox call (Task 3/4's parsing logic is written defensively with documented fallbacks, but should be re-verified once real payloads exist — same as the `GROW_USER_ID`/`GROW_PAGE_CODE` Railway env vars, which stay unset until then)
- Setting Railway production env vars and flipping `grow.base-url` to the production host
- Asking Grow directly about settlement timing/fees to the bank account (a contract term, not something this code touches)
