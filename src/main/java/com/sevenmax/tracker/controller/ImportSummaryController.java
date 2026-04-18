package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.PlayerTransfer;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/import-summary")
@RequiredArgsConstructor
public class ImportSummaryController {

    private final ImportSummaryRepository importSummaryRepository;
    private final PlayerTransferRepository playerTransferRepository;

    // PATCH /api/import-summary/bank-balance — manually set bankDeposits to a specific value
    @PatchMapping("/bank-balance")
    public ResponseEntity<?> setBankBalance(@RequestBody Map<String, Object> body, Authentication auth) {
        if (body.get("bankBalance") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "bankBalance is required"));
        }
        BigDecimal newBalance;
        try {
            newBalance = new BigDecimal(body.get("bankBalance").toString());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid bankBalance"));
        }

        ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
        summary.setId(1L);
        summary.setBankDeposits(newBalance);
        summary.setLastUpdated(java.time.LocalDateTime.now());
        importSummaryRepository.save(summary);

        // Audit log entry as a PlayerTransfer with method=ADJUSTMENT
        PlayerTransfer audit = new PlayerTransfer();
        audit.setAmount(newBalance);
        audit.setMethod(Transaction.Method.ADJUSTMENT);
        audit.setNotes("Manual bank balance correction");
        audit.setTransferDate(LocalDate.now());
        audit.setCreatedByUsername(auth != null ? auth.getName() : "system");
        audit.setConfirmed(true);
        playerTransferRepository.save(audit);

        return ResponseEntity.ok(Map.of("bankBalance", newBalance));
    }
}
