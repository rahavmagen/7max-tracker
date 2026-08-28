# WhatsApp (Green API) Health Check & Alerting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect when the Green API WhatsApp instance stops being `authorized` and email an alert immediately, so a stuck/unpaid/logged-out session is never silently missed again.

**Architecture:** A new `@Scheduled` component (`WhatsAppHealthScheduler`) polls a new `WhatsAppService.checkInstanceState()` method every 30 minutes, feeds the reading into a pure decision function (`WhatsAppHealthEvaluator`) that compares it against the last-known state, and emails the existing admin notification list via `GmailEmailService` on any down/recovered transition (plus a once-per-24h reminder while still down).

**Tech Stack:** Spring Boot (Java 21), `java.net.http.HttpClient`, Jackson, JUnit 5 + AssertJ (no Mockito needed — the new logic is pure).

**Spec:** `docs/superpowers/specs/2026-08-28-whatsapp-health-check-design.md`

---

### Task 1: `WhatsAppHealthEvaluator` — pure alerting decision logic (TDD)

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/service/WhatsAppHealthEvaluator.java`
- Test: `src/test/java/com/sevenmax/tracker/service/WhatsAppHealthEvaluatorTest.java`

This is the only piece of new logic with real branching, so it's the only piece worth unit testing — matches the existing pattern of `XlsMatchingUnitTest` (plain instantiation, no mocks, AssertJ assertions).

- [ ] **Step 1: Write the failing test**

```java
package com.sevenmax.tracker.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.sevenmax.tracker.service.WhatsAppHealthEvaluator.HealthAction.*;
import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppHealthEvaluatorTest {

    private final WhatsAppHealthEvaluator evaluator = new WhatsAppHealthEvaluator();
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 28, 12, 0);

    @Test
    void firstCheckEver_alreadyBroken_alertsDown() {
        assertThat(evaluator.evaluate(null, "notAuthorized", null, now)).isEqualTo(ALERT_DOWN);
    }

    @Test
    void firstCheckEver_healthy_noAlert() {
        assertThat(evaluator.evaluate(null, "authorized", null, now)).isEqualTo(NONE);
    }

    @Test
    void wasHealthy_nowBroken_alertsDown() {
        assertThat(evaluator.evaluate("authorized", "notAuthorized", null, now)).isEqualTo(ALERT_DOWN);
    }

    @Test
    void wasBroken_nowHealthy_alertsRecovered() {
        LocalDateTime lastAlert = now.minusHours(1);
        assertThat(evaluator.evaluate("notAuthorized", "authorized", lastAlert, now)).isEqualTo(ALERT_RECOVERED);
    }

    @Test
    void stillBroken_recentAlert_noReminderYet() {
        LocalDateTime lastAlert = now.minusHours(2);
        assertThat(evaluator.evaluate("notAuthorized", "notAuthorized", lastAlert, now)).isEqualTo(NONE);
    }

    @Test
    void stillBroken_24hSinceLastAlert_sendsReminder() {
        LocalDateTime lastAlert = now.minusHours(25);
        assertThat(evaluator.evaluate("notAuthorized", "notAuthorized", lastAlert, now)).isEqualTo(ALERT_REMINDER);
    }

    @Test
    void stillHealthy_noAlert() {
        assertThat(evaluator.evaluate("authorized", "authorized", null, now)).isEqualTo(NONE);
    }

    @Test
    void otherUnhealthyStates_blockedAndSleepMode_alertDown() {
        assertThat(evaluator.evaluate("authorized", "blocked", null, now)).isEqualTo(ALERT_DOWN);
        assertThat(evaluator.evaluate("authorized", "sleepMode", null, now)).isEqualTo(ALERT_DOWN);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd c:/projects/tracker && mvn -q test -Dtest=WhatsAppHealthEvaluatorTest`
Expected: FAIL — compile error, `WhatsAppHealthEvaluator` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.sevenmax.tracker.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Pure decision logic for whether a Green API instance-state reading should trigger an
 * alert email. No I/O — the caller supplies the previous/current state strings and
 * timestamps and gets back what to do. See docs/superpowers/specs/2026-08-28-whatsapp-health-check-design.md
 * for the transition table this implements.
 */
public class WhatsAppHealthEvaluator {

    public enum HealthAction { NONE, ALERT_DOWN, ALERT_REMINDER, ALERT_RECOVERED }

    private static final String AUTHORIZED = "authorized";
    private static final Duration REMINDER_INTERVAL = Duration.ofHours(24);

    public HealthAction evaluate(String previousState, String currentState, LocalDateTime lastAlertAt, LocalDateTime now) {
        boolean isHealthy = AUTHORIZED.equals(currentState);

        if (previousState == null) {
            return isHealthy ? HealthAction.NONE : HealthAction.ALERT_DOWN;
        }

        boolean wasHealthy = AUTHORIZED.equals(previousState);

        if (wasHealthy && !isHealthy) return HealthAction.ALERT_DOWN;
        if (!wasHealthy && isHealthy) return HealthAction.ALERT_RECOVERED;
        if (!isHealthy && lastAlertAt != null
                && Duration.between(lastAlertAt, now).compareTo(REMINDER_INTERVAL) >= 0) {
            return HealthAction.ALERT_REMINDER;
        }
        return HealthAction.NONE;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd c:/projects/tracker && mvn -q test -Dtest=WhatsAppHealthEvaluatorTest`
Expected: PASS — all 9 tests green.

- [ ] **Step 5: Commit**

```bash
cd c:/projects/tracker
git add src/main/java/com/sevenmax/tracker/service/WhatsAppHealthEvaluator.java src/test/java/com/sevenmax/tracker/service/WhatsAppHealthEvaluatorTest.java
git commit -m "feat: add WhatsAppHealthEvaluator for Green API state-change alerting decisions"
```

---

### Task 2: `WhatsAppService.checkInstanceState()` — query Green API instance state

**Files:**
- Modify: `src/main/java/com/sevenmax/tracker/service/WhatsAppService.java:81` (insert before the final closing brace, after `formatChatId`)

No test for this step — it's a direct HTTP call to Green API, same as `sendOne` above it in the same file, which also has no unit test (no test framework in this codebase mocks `java.net.http.HttpClient`; verified manually in Task 5 instead).

- [ ] **Step 1: Add the method**

Insert this method into `WhatsAppService.java`, immediately after `formatChatId` (i.e. right before the class's closing `}` on line 82):

```java

    /**
     * Queries Green API for this instance's authorization state
     * (authorized / notAuthorized / blocked / sleepMode / starting / ...).
     * Throws on any HTTP or parsing failure — callers must treat that as "unknown", not "down".
     */
    public String checkInstanceState() throws Exception {
        String url = String.format(
            "%s/waInstance%s/getStateInstance/%s",
            apiUrl, instanceId, token
        );
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("getStateInstance HTTP " + response.statusCode() + ": " + response.body());
        }
        com.fasterxml.jackson.databind.JsonNode json = MAPPER.readTree(response.body());
        if (!json.has("stateInstance")) {
            throw new RuntimeException("getStateInstance response missing stateInstance field: " + response.body());
        }
        return json.get("stateInstance").asText();
    }
```

- [ ] **Step 2: Compile to verify it builds**

Run: `cd c:/projects/tracker && mvn -q compile`
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 3: Commit**

```bash
cd c:/projects/tracker
git add src/main/java/com/sevenmax/tracker/service/WhatsAppService.java
git commit -m "feat: add WhatsAppService.checkInstanceState() for Green API health polling"
```

---

### Task 3: `WhatsAppHealthScheduler` — wire the poll + evaluator + email alert together

**Files:**
- Create: `src/main/java/com/sevenmax/tracker/scheduler/WhatsAppHealthScheduler.java`
- Modify: `src/main/resources/application.properties:39` (insert new line after `app.grow.notification-emails=${NOTIFY_EMAILS:}`)

- [ ] **Step 1: Add the config line**

In `application.properties`, immediately after line 39 (`app.grow.notification-emails=${NOTIFY_EMAILS:}`), add:

```properties
app.whatsapp-health.notification-emails=${NOTIFY_EMAILS:}
```

- [ ] **Step 2: Create the scheduler**

```java
package com.sevenmax.tracker.scheduler;

import com.sevenmax.tracker.service.GmailEmailService;
import com.sevenmax.tracker.service.WhatsAppHealthEvaluator;
import com.sevenmax.tracker.service.WhatsAppHealthEvaluator.HealthAction;
import com.sevenmax.tracker.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Polls Green API's instance state every 30 minutes and emails the admin list on any
 * down/recovered transition (plus a once-per-24h reminder while still down). See
 * docs/superpowers/specs/2026-08-28-whatsapp-health-check-design.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppHealthScheduler {

    private final WhatsAppService whatsAppService;
    private final GmailEmailService gmailEmailService;
    private final WhatsAppHealthEvaluator evaluator = new WhatsAppHealthEvaluator();

    @Value("${app.whatsapp-health.notification-emails:}")
    private String notificationEmails;

    private String previousState;
    private LocalDateTime lastAlertAt;

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void checkHealth() {
        String currentState;
        try {
            currentState = whatsAppService.checkInstanceState();
        } catch (Exception e) {
            log.warn("WhatsApp health check failed (treating as transient, not down): {}", e.getMessage());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        HealthAction action = evaluator.evaluate(previousState, currentState, lastAlertAt, now);

        switch (action) {
            case ALERT_DOWN -> {
                log.error("WhatsApp (Green API) is DOWN — state: {}", currentState);
                sendAlert("WhatsApp (Green API) is down — state: " + currentState);
                lastAlertAt = now;
            }
            case ALERT_REMINDER -> {
                log.warn("WhatsApp (Green API) still down — state: {}", currentState);
                sendAlert("WhatsApp (Green API) is still down — state: " + currentState);
                lastAlertAt = now;
            }
            case ALERT_RECOVERED -> {
                log.info("WhatsApp (Green API) recovered — state: {}", currentState);
                sendAlert("WhatsApp (Green API) has recovered — state: " + currentState);
                lastAlertAt = null;
            }
            case NONE -> { /* no state change worth emailing about */ }
        }
        previousState = currentState;
    }

    private void sendAlert(String message) {
        if (notificationEmails == null || notificationEmails.isBlank()) return;
        List<String> recipients = Arrays.stream(notificationEmails.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (recipients.isEmpty()) return;
        gmailEmailService.send(recipients, "7MAX WhatsApp Alert", message);
    }
}
```

- [ ] **Step 3: Compile to verify it builds**

Run: `cd c:/projects/tracker && mvn -q compile`
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 4: Commit**

```bash
cd c:/projects/tracker
git add src/main/java/com/sevenmax/tracker/scheduler/WhatsAppHealthScheduler.java src/main/resources/application.properties
git commit -m "feat: add WhatsAppHealthScheduler to alert on Green API instance state changes"
```

---

### Task 4: Manual end-to-end verification

No automated test covers the live HTTP call (consistent with the rest of `WhatsAppService` — see Task 2). Verify manually, per the spec's Verification section:

- [ ] **Step 1: Run the app locally against the real (currently lapsed) Green API credentials**

Run: `cd c:/projects/tracker && mvn spring-boot:run`
Expected in logs within a few seconds of startup (first `@Scheduled` tick fires once at startup + every 30 min): a line matching `WhatsApp (Green API) is DOWN — state: notAuthorized` (or whatever the real current state is).

- [ ] **Step 2: Confirm the alert email arrived**

Check the inbox(es) listed in the `NOTIFY_EMAILS` Railway env var for a message with subject "7MAX WhatsApp Alert" and body "WhatsApp (Green API) is down — state: ...".
Expected: email received within a minute of the log line in Step 1.

- [ ] **Step 3: Resume the Green API subscription / re-authorize the instance, then confirm recovery detection**

After re-authorizing (this depends on the user resuming payment and re-linking via QR if needed — a manual action outside this codebase), wait for the next scheduled tick (or restart the app to force an immediate check).
Expected: log line `WhatsApp (Green API) recovered — state: authorized` and a "has recovered" email.

- [ ] **Step 4: Confirm no duplicate alerts on repeated down checks within 24h**

With the instance still down, restart the app or wait for a second tick within 24 hours of the first alert.
Expected: no second email; `previousState` is already `notAuthorized` so the evaluator returns `NONE` (reminder only fires after 24h — this was already covered by the `stillBroken_recentAlert_noReminderYet` unit test in Task 1, this step just confirms it live).

---

## Self-Review Notes

- **Spec coverage:** every row of the spec's "Alerting Logic" table has a corresponding unit test in Task 1 (`ALERT_DOWN` on transition and on first-check-already-broken, `ALERT_RECOVERED`, `ALERT_REMINDER` after 24h, `NONE` for no-change and for a too-recent reminder window). The spec's "no new Railway env vars" and "reuse `GmailEmailService`" constraints are reflected in Task 3 (reuses `NOTIFY_EMAILS`, reuses `GmailEmailService.send`). The spec's error-handling requirement ("transient failure must not overwrite state or false-alarm") is implemented by the try/catch + early `return` in `checkHealth()` before `previousState` is ever reassigned.
- **Placeholder scan:** none — every step has complete, runnable code and exact commands.
- **Type consistency:** `WhatsAppHealthEvaluator.HealthAction` (Task 1) is the exact type referenced and switched over in `WhatsAppHealthScheduler` (Task 3); `checkInstanceState()`'s return type (`String`, throws `Exception`) matches how it's called and caught in the scheduler.
