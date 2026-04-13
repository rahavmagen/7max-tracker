package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.service.PlayerTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlayerTransferController {

    private final PlayerTransferService transferService;
    private final PlayerTransferRepository transferRepository;
    private final ImportSummaryRepository importSummaryRepository;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Long fromPlayerId = body.get("fromPlayerId") != null ? ((Number) body.get("fromPlayerId")).longValue() : null;
            Long fromBankAccountId = body.get("fromBankAccountId") != null ? ((Number) body.get("fromBankAccountId")).longValue() : null;
            Long toPlayerId = body.get("toPlayerId") != null ? ((Number) body.get("toPlayerId")).longValue() : null;
            Long toBankAccountId = body.get("toBankAccountId") != null ? ((Number) body.get("toBankAccountId")).longValue() : null;
            BigDecimal amount = new BigDecimal(body.get("amount").toString());
            Transaction.Method method = Transaction.Method.valueOf(body.get("method").toString());
            String notes = (String) body.get("notes");
            String createdBy = auth != null ? auth.getName() : null;
            var transfer = transferService.createTransfer(fromPlayerId, fromBankAccountId, toPlayerId, toBankAccountId, amount, method, notes, createdBy);
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

    @GetMapping("/all-pending")
    public List<Map<String, Object>> getAllPending() {
        return transferService.getAllPending();
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            BigDecimal amount = new BigDecimal(body.get("amount").toString());
            String notes = (String) body.get("notes");
            Transaction.Method method = body.get("method") != null ? Transaction.Method.valueOf(body.get("method").toString()) : null;
            var transfer = transferService.updateTransfer(id, amount, notes, method);
            return ResponseEntity.ok(transferService.toDto(transfer));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/settlement")
    public ResponseEntity<?> createPayment(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Long fromPlayerId = body.get("fromPlayerId") != null ? ((Number) body.get("fromPlayerId")).longValue() : null;
            Long toPlayerId = body.get("toPlayerId") != null ? ((Number) body.get("toPlayerId")).longValue() : null;
            BigDecimal amount = new BigDecimal(body.get("amount").toString());
            Transaction.Method method = Transaction.Method.valueOf(body.get("method").toString());
            String notes = (String) body.get("notes");
            String createdBy = auth != null ? auth.getName() : null;
            var transfer = transferService.createPayment(fromPlayerId, toPlayerId, amount, method, notes, createdBy);
            return ResponseEntity.ok(transferService.toDto(transfer));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    @GetMapping("/last-night-mtt")
    public ResponseEntity<?> getLastNightMtt(@RequestParam(required = false) String date) {
        LocalDate d = (date != null && !date.isBlank()) ? LocalDate.parse(date) : LocalDate.now().minusDays(1);
        Map<String, Object> result = transferService.getLastNightMtt(d);
        if (result == null) return ResponseEntity.ok(Map.of());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/bank-history")
    public ResponseEntity<?> getBankHistory() {
        var transfers = transferRepository.findBankRelatedTransfers();
        var summary = importSummaryRepository.findById(1L).orElse(null);

        // Compute XLS base = current bankDeposits - sum of all bank transfer deltas
        java.math.BigDecimal transferSum = java.math.BigDecimal.ZERO;
        for (var t : transfers) {
            boolean toBank = t.getToBankAccount() != null || (t.getToPlayer() == null && t.getFromPlayer() != null);
            transferSum = toBank ? transferSum.add(t.getAmount()) : transferSum.subtract(t.getAmount());
        }
        java.math.BigDecimal currentBank = summary != null && summary.getBankDeposits() != null ? summary.getBankDeposits() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal xlsBase = currentBank.subtract(transferSum);

        var rows = new java.util.ArrayList<Map<String, Object>>();
        if (xlsBase.compareTo(java.math.BigDecimal.ZERO) != 0) {
            rows.add(Map.of("type", "XLS", "description", "XLS base (מיקום הכסף)", "delta", xlsBase, "date", ""));
        }
        for (var t : transfers) {
            boolean toBank = t.getToBankAccount() != null || (t.getToPlayer() == null && t.getFromPlayer() != null);
            java.math.BigDecimal delta = toBank ? t.getAmount() : t.getAmount().negate();
            String fromName = t.getFromPlayer() != null ? t.getFromPlayer().getUsername()
                    : (t.getFromBankAccount() != null ? t.getFromBankAccount().getName() : "CLUB");
            String toName = t.getToPlayer() != null ? t.getToPlayer().getUsername()
                    : (t.getToBankAccount() != null ? t.getToBankAccount().getName() : "CLUB");
            var row = new java.util.HashMap<String, Object>();
            row.put("id", t.getId());
            row.put("type", "TRANSFER");
            row.put("date", t.getTransferDate() != null ? t.getTransferDate().toString() : "");
            row.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "");
            row.put("fromName", fromName);
            row.put("toName", toName);
            row.put("fromPlayerId", t.getFromPlayer() != null ? t.getFromPlayer().getId() : null);
            row.put("toPlayerId", t.getToPlayer() != null ? t.getToPlayer().getId() : null);
            row.put("delta", delta);
            row.put("method", t.getMethod() != null ? t.getMethod().toString() : "");
            row.put("notes", t.getNotes() != null ? t.getNotes() : "");
            row.put("createdBy", t.getCreatedByUsername() != null ? t.getCreatedByUsername() : "");
            rows.add(row);
        }
        java.util.Collections.reverse(rows); // newest first
        return ResponseEntity.ok(Map.of("rows", rows, "total", currentBank));
    }
}
