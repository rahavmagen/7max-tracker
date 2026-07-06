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

    // PATCH /api/import-summary/bank-balance — manually correct bankDeposits to a specific value.
    // Records the delta as an audit PlayerTransfer (method=ADJUSTMENT) so it shows up in Bank history.
    @PatchMapping("/bank-balance")
    public ResponseEntity<?> setBankBalance(@RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();

        if (body.get("bankBalance") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "bankBalance is required"));
        }
        BigDecimal newBalance;
        try {
            newBalance = new BigDecimal(body.get("bankBalance").toString());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid bankBalance"));
        }
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "bankBalance cannot be negative"));
        }

        ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
        summary.setId(1L);
        BigDecimal previousBalance = summary.getBankDeposits() != null ? summary.getBankDeposits() : BigDecimal.ZERO;
        BigDecimal delta = newBalance.subtract(previousBalance);

        summary.setBankDeposits(newBalance);
        summary.setLastUpdated(java.time.LocalDateTime.now());
        importSummaryRepository.save(summary);

        String customNotes = body.get("notes") != null ? body.get("notes").toString().trim() : "";
        String autoNote = "Balance corrected: " + previousBalance + " -> " + newBalance;
        String notes = customNotes.isEmpty() ? autoNote : autoNote + " - " + customNotes;

        // Audit log entry as a PlayerTransfer with method=ADJUSTMENT; amount stores the signed delta
        // so it merges correctly into the Bank history reconciliation (findBankRelatedTransfers).
        PlayerTransfer audit = new PlayerTransfer();
        audit.setAmount(delta);
        audit.setMethod(Transaction.Method.ADJUSTMENT);
        audit.setNotes(notes);
        audit.setTransferDate(LocalDate.now());
        audit.setCreatedByUsername(auth != null ? auth.getName() : "system");
        audit.setConfirmed(true);
        playerTransferRepository.save(audit);

        return ResponseEntity.ok(Map.of("previousBalance", previousBalance, "newBalance", newBalance, "delta", delta));
    }

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }
}
