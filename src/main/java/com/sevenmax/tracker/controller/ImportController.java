package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.repository.*;
import com.sevenmax.tracker.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/reset-all")
    public ResponseEntity<Map<String, Object>> resetAll() {
        long players = playerRepository.count();
        gameResultRepository.deleteAll();
        gameSessionRepository.deleteAll();
        reportRepository.deleteAll();
        transactionRepository.deleteAll();
        playerRepository.deleteAll();
        importSummaryRepository.deleteAll();
        return ResponseEntity.ok(Map.of("deleted", Map.of("players", players)));
    }

    @GetMapping("/profit-summary")
    public ResponseEntity<ImportSummary> getProfitSummary() {
        return importSummaryRepository.findById(1L)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}
