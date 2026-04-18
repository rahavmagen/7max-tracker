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
