package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.service.AdminDepositService;
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
@RequestMapping("/api/admin-deposits")
@RequiredArgsConstructor
public class AdminDepositController {

    private final AdminDepositService adminDepositService;
    private final UserRepository userRepository;

    /** ADMIN/MANAGER or a worker: create a manual deposit for an existing player (playerId) or a
     *  same-day joiner not yet in the system (newPlayerUsername) - exactly one of the two. */
    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        if (!isAdminManagerOrWorker(auth)) return ResponseEntity.status(403).build();
        try {
            Object playerIdObj = body.get("playerId");
            Long playerId = playerIdObj == null ? null : Long.valueOf(playerIdObj.toString());
            String newPlayerUsername = (String) body.get("newPlayerUsername");
            Object amountObj = body.get("amount");
            if (amountObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing required field: amount"));
            }
            BigDecimal amount = new BigDecimal(amountObj.toString());
            String note = (String) body.get("note");

            Transaction tx = adminDepositService.createDeposit(playerId, newPlayerUsername, amount, note, auth.getName());
            return ResponseEntity.ok(Map.of("success", true, "id", tx.getId(),
                    "username", tx.getPlayer().getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Admin deposit create error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pending")
    public ResponseEntity<?> getPending(Authentication auth) {
        if (!isAdminManagerOrWorker(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(adminDepositService.getPending());
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication auth) {
        if (!isAdminManagerOrWorker(auth)) return ResponseEntity.status(403).build();
        try {
            LocalDate fromDate = from != null ? LocalDate.parse(from) : null;
            LocalDate toDate = to != null ? LocalDate.parse(to) : null;
            return ResponseEntity.ok(adminDepositService.getHistory(fromDate, toDate));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/confirm/{id}")
    public ResponseEntity<?> confirmChips(@PathVariable Long id, Authentication auth) {
        if (!isAdminManagerOrWorker(auth)) return ResponseEntity.status(403).build();
        try {
            adminDepositService.confirmChips(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isAdminManagerOrWorker(Authentication auth) {
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null) return false;
        if (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MANAGER) return true;
        return user.getPlayer() != null && Boolean.TRUE.equals(user.getPlayer().getIsWorker());
    }
}
