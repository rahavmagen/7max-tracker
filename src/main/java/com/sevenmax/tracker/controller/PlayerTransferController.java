package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.service.PlayerTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlayerTransferController {

    private final PlayerTransferService transferService;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Long fromPlayerId = body.get("fromPlayerId") != null ? ((Number) body.get("fromPlayerId")).longValue() : null;
            Long toPlayerId = body.get("toPlayerId") != null ? ((Number) body.get("toPlayerId")).longValue() : null;
            BigDecimal amount = new BigDecimal(body.get("amount").toString());
            Transaction.Method method = Transaction.Method.valueOf(body.get("method").toString());
            String notes = (String) body.get("notes");
            String createdBy = auth != null ? auth.getName() : null;
            var transfer = transferService.createTransfer(fromPlayerId, toPlayerId, amount, method, notes, createdBy);
            return ResponseEntity.ok(transferService.toDto(transfer));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pending")
    public List<Map<String, Object>> getPending() {
        return transferService.getPendingTransfers().stream()
                .map(transferService::toDto)
                .collect(Collectors.toList());
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable Long id, Authentication auth) {
        try {
            transferService.confirmTransfer(id, auth != null ? auth.getName() : null);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
