package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.service.GrowDepositService;
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
@RequiredArgsConstructor
public class GrowDepositController {

    private final GrowDepositService growDepositService;
    private final UserRepository userRepository;

    /** PLAYER: initiate a Grow deposit — creates a payment link via the Make.com scenario */
    @PostMapping("/api/grow-deposits/initiate")
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
            Map<String, Object> result = growDepositService.initiateDeposit(user.getPlayer().getId(), amount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Grow initiate error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PUBLIC: Grow's direct server-to-server payment callback — always returns 200 so Grow does not retry */
    @PostMapping("/api/grow/webhook")
    public ResponseEntity<?> webhook(@RequestBody Map<String, Object> payload) {
        try {
            growDepositService.handleWebhook(payload);
        } catch (Exception e) {
            log.error("Grow webhook error: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok(Map.of("received", true));
    }

    /** ADMIN/MANAGER: list deposits where chipsConfirmed=false */
    @GetMapping("/api/grow-deposits/pending")
    public ResponseEntity<?> getPending(Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(growDepositService.getPending());
    }

    /** ADMIN/MANAGER: full history with optional ?from=yyyy-MM-dd&to=yyyy-MM-dd */
    @GetMapping("/api/grow-deposits/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        try {
            LocalDate fromDate = from != null ? LocalDate.parse(from) : null;
            LocalDate toDate = to != null ? LocalDate.parse(to) : null;
            return ResponseEntity.ok(growDepositService.getHistory(fromDate, toDate));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** ADMIN/MANAGER: mark chips as added for a deposit transaction */
    @PostMapping("/api/grow-deposits/confirm/{id}")
    public ResponseEntity<?> confirmChips(@PathVariable Long id, Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        try {
            growDepositService.confirmChips(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PLAYER: own Grow deposit history */
    @GetMapping("/api/grow-deposits/my")
    public ResponseEntity<?> myDeposits(Authentication auth) {
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null || user.getPlayer() == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(growDepositService.getMyDeposits(user.getPlayer().getId()));
    }

    private boolean isAdminOrManager(Authentication auth) {
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        return user != null && (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MANAGER);
    }
}
