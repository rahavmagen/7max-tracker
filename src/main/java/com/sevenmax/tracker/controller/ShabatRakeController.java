package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.ShabatRake;
import com.sevenmax.tracker.repository.ShabatRakeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shabat-rake")
@RequiredArgsConstructor
public class ShabatRakeController {

    private final ShabatRakeRepository shabatRakeRepository;

    @GetMapping("/latest")
    public ResponseEntity<?> getLatest() {
        return shabatRakeRepository.findTopByOrderByCreatedAtDesc()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<ShabatRake>> getHistory(Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(shabatRakeRepository.findAllByOrderByCreatedAtDesc());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();

        String amountStr = body.getOrDefault("amount", "0").toString();
        String reason = body.getOrDefault("reason", "").toString();
        String dateStr = body.getOrDefault("date", LocalDateTime.now().toString()).toString();

        ShabatRake entry = new ShabatRake();
        entry.setAmount(new java.math.BigDecimal(amountStr));
        entry.setReason(reason);
        entry.setDate(LocalDateTime.parse(dateStr.length() == 10 ? dateStr + "T00:00:00" : dateStr));
        entry.setCreatedBy(auth.getName());

        return ResponseEntity.ok(shabatRakeRepository.save(entry));
    }

    private boolean isAdminOrManager(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"));
    }
}
