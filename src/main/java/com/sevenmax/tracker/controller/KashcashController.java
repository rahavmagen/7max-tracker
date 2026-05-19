package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.service.KashcashService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/kashcash")
@RequiredArgsConstructor
public class KashcashController {

    private final KashcashService kashcashService;
    private final UserRepository userRepository;

    /** PLAYER: initiate a KashCash deposit */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiate(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            BigDecimal amount = new BigDecimal(body.get("amount").toString());
            if (amount.compareTo(BigDecimal.ONE) < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Minimum deposit is 1"));
            }
            User user = userRepository.findByUsername(auth.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            if (user.getPlayer() == null) {
                return ResponseEntity.status(403).body(Map.of("error", "No player linked to this account"));
            }
            Map<String, String> result = kashcashService.initiateDeposit(user.getPlayer().getId(), amount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("KashCash initiate error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PUBLIC: KashCash webhook callback — always returns 200 so KashCash does not retry */
    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(@RequestBody Map<String, Object> payload) {
        try {
            kashcashService.handleWebhook(payload);
        } catch (Exception e) {
            log.error("KashCash webhook error: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok(Map.of("received", true));
    }

    /** ADMIN/MANAGER: list deposits where chipsConfirmed=false */
    @GetMapping("/pending")
    public ResponseEntity<?> getPending(Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(kashcashService.getPending());
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
            return ResponseEntity.ok(kashcashService.getHistory(fromDate, toDate));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** ADMIN/MANAGER: mark chips as added for a deposit transaction */
    @PostMapping("/confirm/{id}")
    public ResponseEntity<?> confirmChips(@PathVariable Long id, Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        try {
            kashcashService.confirmChips(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PLAYER: own KashCash deposit history */
    @GetMapping("/my")
    public ResponseEntity<?> myDeposits(Authentication auth) {
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null || user.getPlayer() == null) return ResponseEntity.ok(java.util.List.of());
        return ResponseEntity.ok(kashcashService.getMyDeposits(user.getPlayer().getId()));
    }

    private boolean isAdminOrManager(Authentication auth) {
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        return user != null && (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MANAGER);
    }
}
