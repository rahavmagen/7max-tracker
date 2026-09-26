package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.service.DepositWaitService;
import com.sevenmax.tracker.service.GmailEmailService;
import com.sevenmax.tracker.service.GrowDepositService;
import com.sevenmax.tracker.service.KashcashService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;

/**
 * Machine-to-machine endpoints for the chip-loading automation (runs on a separate always-on PC,
 * not through any admin's login) - authenticated by a static API key, same pattern as
 * ReportController's /upload-auto, rather than a stored user password.
 */
@Slf4j
@RestController
@RequestMapping("/api/deposit-automation")
@RequiredArgsConstructor
public class DepositAutomationController {

    private final DepositWaitService depositWaitService;
    private final GrowDepositService growDepositService;
    private final KashcashService kashcashService;
    private final GmailEmailService gmailEmailService;

    private static final String API_KEY = "sevenmax-deposit-auto-2026-qT7mN";
    private static final List<String> ALERT_RECIPIENT = List.of("rahavm@gmail.com");
    private static final long POLL_TIMEOUT_MS = 25_000;

    /** Long-poll: returns immediately if there's already a pending deposit, otherwise holds the
     *  connection open until one arrives or POLL_TIMEOUT_MS elapses (returns an empty list on
     *  timeout - the caller should immediately call again). */
    @GetMapping("/wait-for-pending")
    public DeferredResult<List<Map<String, Object>>> waitForPending(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        if (!API_KEY.equals(apiKey)) {
            DeferredResult<List<Map<String, Object>>> denied = new DeferredResult<>();
            denied.setErrorResult(ResponseEntity.status(401).body(Map.of("error", "Invalid API key")));
            return denied;
        }
        return depositWaitService.waitForPending(POLL_TIMEOUT_MS);
    }

    /** Mark a Grow or KashCash deposit's chips as loaded. source = "grow" | "kashcash". */
    @PostMapping("/confirm/{source}/{id}")
    public ResponseEntity<?> confirm(
            @PathVariable String source,
            @PathVariable Long id,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        if (!API_KEY.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid API key"));
        }
        try {
            Transaction tx = switch (source.toLowerCase()) {
                case "grow" -> growDepositService.confirmChips(id);
                case "kashcash" -> kashcashService.confirmChips(id);
                default -> null;
            };
            if (tx == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "source must be 'grow' or 'kashcash'"));
            }
            log.info("Deposit automation: confirmed {} deposit id={}", source, id);
            sendAutoLoadedEmail(source, tx);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Deposit automation confirm failed: source={} id={} error={}", source, id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Fallback trigger while Grow's real webhook doesn't fire: called by the email watcher after
     *  it parses one of Grow's own confirmation emails. Body: { emailLocal, amount }. The backend
     *  matches this against real usernames on file - the caller never has to guess/reverse the
     *  sanitized email text back into an actual username. */
    @PostMapping("/trigger-grow-from-email")
    public ResponseEntity<?> triggerGrowFromEmail(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        if (!API_KEY.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid API key"));
        }
        try {
            String emailLocal = String.valueOf(body.get("emailLocal"));
            java.math.BigDecimal amount = new java.math.BigDecimal(String.valueOf(body.get("amount")));
            boolean matched = growDepositService.processFromEmailFallback(emailLocal, amount);
            if (!matched) {
                return ResponseEntity.status(404).body(Map.of("error", "no unambiguous matching pending deposit found"));
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("trigger-grow-from-email failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** What the automation PC reports when it gives up on a deposit. The instruction differs by kind -
     *  getting it wrong could make someone load chips a second time. */
    private static final Map<String, String> FAILURE_INSTRUCTIONS = Map.of(
            "wrong_player", "הצ'יפים לא נשלחו - החיפוש הגיע לשחקן אחר. יש לטעון ידנית.",
            "max_retries", "הצ'יפים לא נשלחו - הטעינה האוטומטית נכשלה כמה פעמים. יש לטעון ידנית.",
            "over_cap", "הצ'יפים לא נשלחו - הסכום גבוה מהתקרה לטעינה אוטומטית. יש לבדוק ולטעון ידנית.",
            "unverified", "ייתכן שהצ'יפים כבר נשלחו - לא הופיעה הודעת הצלחה. לפני טעינה ידנית יש לבדוק ב-Trade Record ב-ClubGG, כדי לא לטעון פעמיים.",
            "confirm_failed", "הצ'יפים כבר נשלחו ב-ClubGG, רק האישור באתר נכשל. אין לטעון שוב - רק לאשר את ההפקדה באתר.");

    /** The automation gave up on a deposit: email a Hebrew "handle manually" alert.
     *  Body: { username, amount, kind, reason } - kind is one of FAILURE_INSTRUCTIONS' keys. */
    @PostMapping("/report-failure/{source}/{id}")
    public ResponseEntity<?> reportFailure(
            @PathVariable String source,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        if (!API_KEY.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid API key"));
        }
        String kind = String.valueOf(body.get("kind"));
        String instruction = FAILURE_INSTRUCTIONS.get(kind);
        if (instruction == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown kind: " + kind));
        }
        String username = String.valueOf(body.get("username"));
        String amount = String.valueOf(body.get("amount"));
        String subject = String.format("7MAX - טעינה אוטומטית נכשלה, נדרש טיפול ידני (%s): %s ₪%s",
                source.toUpperCase(), username, amount);
        String text = String.format(
                "הטעינה האוטומטית של הצ'יפים לא הושלמה, ונדרש טיפול ידני.%n%n"
                + "%s%n%n"
                + "מקור: %s%nשחקן: %s%nסכום: ₪%s%nמזהה הפקדה: %d%n%n"
                + "ההפקדה נשארה פתוחה באתר, והמערכת לא תנסה לטעון אותה שוב אוטומטית. "
                + "אחרי הטיפול יש לאשר אותה באתר.%n%n"
                + "פרטים טכניים: %s",
                instruction, source.toUpperCase(), username, amount, id, String.valueOf(body.get("reason")));
        boolean sent = gmailEmailService.send(ALERT_RECIPIENT, subject, text);
        log.info("Deposit automation: failure reported for {} deposit id={} kind={} emailed={}", source, id, kind, sent);
        return sent ? ResponseEntity.ok(Map.of("success", true))
                    : ResponseEntity.status(502).body(Map.of("error", "email not sent"));
    }

    private void sendAutoLoadedEmail(String source, Transaction tx) {
        try {
            String subject = String.format("7MAX - Chips auto-loaded (%s): %s ₪%s",
                    source.toUpperCase(), tx.getPlayer().getUsername(), tx.getAmount().toPlainString());
            String body = String.format(
                    "Chips were loaded automatically - no need to load them manually.%n%n"
                    + "Source: %s%nPlayer: %s%nAmount: ₪%s%nTransaction ID: %d",
                    source.toUpperCase(), tx.getPlayer().getUsername(), tx.getAmount().toPlainString(), tx.getId());
            gmailEmailService.send(ALERT_RECIPIENT, subject, body);
        } catch (Exception e) {
            log.error("Failed to send auto-loaded confirmation email: {}", e.getMessage());
        }
    }
}
