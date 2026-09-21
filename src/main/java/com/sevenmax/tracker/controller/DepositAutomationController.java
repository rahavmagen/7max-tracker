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
