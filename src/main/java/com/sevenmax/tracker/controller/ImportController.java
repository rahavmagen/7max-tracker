package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.repository.*;
import com.sevenmax.tracker.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ImportController {

    private final ImportService importService;
    private final ImportSummaryRepository importSummaryRepository;
    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final GameResultRepository gameResultRepository;
    private final GameSessionRepository gameSessionRepository;
    private final ReportRepository reportRepository;
    private final PlayerTransferRepository playerTransferRepository;
    private final AdminExpenseRepository adminExpenseRepository;
    private final UserRepository userRepository;

    @PostMapping("/players")
    public ResponseEntity<Map<String, Object>> importPlayers(
            @RequestParam("max7") MultipartFile max7File,
            @RequestParam(value = "clearExisting", defaultValue = "false") boolean clearExisting) {
        try {
            Map<String, Object> result = importService.importFromFiles(max7File, clearExisting);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/compare")
    public ResponseEntity<?> compareWithXls(@RequestParam("max7") MultipartFile max7File) {
        try {
            return ResponseEntity.ok(importService.compareWithXls(max7File));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/expenses-only")
    public ResponseEntity<Map<String, Object>> importExpensesOnly(
            @RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = importService.importExpensesOnly(file);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/wipe")
    @Transactional
    public ResponseEntity<Map<String, Object>> resetAll() {
        long players = playerRepository.count();
        gameResultRepository.deleteAll();
        gameSessionRepository.deleteAll();
        reportRepository.deleteAll();
        transactionRepository.deleteAll();
        playerTransferRepository.deleteAll();
        adminExpenseRepository.deleteAll();
        // Detach users from players before deleting players
        userRepository.detachAllPlayers();
        playerRepository.deleteAll();
        importSummaryRepository.deleteAll();
        return ResponseEntity.ok(Map.of("deleted", Map.of("players", players)));
    }

    @GetMapping("/profit-summary")
    public ResponseEntity<ImportSummary> getProfitSummary() {
        return importSummaryRepository.findById(1L)
                .map(summary -> {
                    // Derive expenses live from AdminExpense table so deletions are reflected immediately
                    java.math.BigDecimal wheelExpenses = adminExpenseRepository.sumByAdminUsername("Wheel");
                    java.math.BigDecimal generalExpenses = adminExpenseRepository.sumExcludingAdminUsername("Wheel");
                    summary.setWillExpense(wheelExpenses != null ? wheelExpenses : java.math.BigDecimal.ZERO);
                    summary.setGeneralExpenses(generalExpenses != null ? generalExpenses : java.math.BigDecimal.ZERO);
                    java.math.BigDecimal promotionsTotal = transactionRepository.sumByTypeName("PROMOTION");
                    summary.setPromotionsTotal(promotionsTotal != null ? promotionsTotal : java.math.BigDecimal.ZERO);
                    java.math.BigDecimal chipPromoTotal = transactionRepository.sumByTypeName("CHIP_PROMO");
                    summary.setChipPromoTotal(chipPromoTotal != null ? chipPromoTotal : java.math.BigDecimal.ZERO);
                    return ResponseEntity.ok(summary);
                })
                .orElse(ResponseEntity.noContent().build());
    }
}
