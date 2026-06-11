package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.ShabatRake;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.ShabatRakeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shabat-rake")
@RequiredArgsConstructor
public class ShabatRakeController {

    private final ShabatRakeRepository shabatRakeRepository;
    private final GameResultRepository gameResultRepository;

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        List<Object[]> fridayRows = gameResultRepository.getFridayRakeReport();
        BigDecimal fridayTotal = fridayRows.stream()
                .map(r -> r[6] != null ? new BigDecimal(r[6].toString()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal bonusPaid = shabatRakeRepository.findAll().stream()
                .map(ShabatRake::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new HashMap<>();
        result.put("fridayRakeTotal", fridayTotal);
        result.put("bonusPaid", bonusPaid);
        result.put("current", fridayTotal.subtract(bonusPaid));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    public ResponseEntity<List<ShabatRake>> getHistory(Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(shabatRakeRepository.findAllByOrderByCreatedAtDesc());
    }

    @PostMapping("/bonus")
    public ResponseEntity<?> addBonus(@RequestBody Map<String, Object> body, Authentication auth) {
        if (!isAdminOrManager(auth)) return ResponseEntity.status(403).build();

        String amountStr = body.getOrDefault("amount", "0").toString();
        String reason = body.getOrDefault("reason", "").toString();
        String playerName = body.getOrDefault("playerName", "").toString();
        String dateStr = body.getOrDefault("date", LocalDateTime.now().toString()).toString();

        ShabatRake entry = new ShabatRake();
        entry.setAmount(new BigDecimal(amountStr));
        entry.setReason(reason);
        entry.setPlayerName(playerName.isBlank() ? null : playerName);
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
