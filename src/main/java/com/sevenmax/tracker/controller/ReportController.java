package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.GameResult;
import com.sevenmax.tracker.entity.Report;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.GameSessionRepository;
import com.sevenmax.tracker.repository.PlayerHandsProjection;
import com.sevenmax.tracker.repository.ReportRepository;
import com.sevenmax.tracker.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final GameResultRepository gameResultRepository;
    private final GameSessionRepository gameSessionRepository;
    private final ReportRepository reportRepository;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadReport(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        try {
            Report report = reportService.uploadReport(file, null);
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Upload failed"));
        }
    }

    @GetMapping
    public ResponseEntity<List<Report>> getAllReports(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(reportService.getAllReports());
    }

    @GetMapping("/player/{playerId}/results")
    public ResponseEntity<List<GameResult>> getPlayerResults(@PathVariable Long playerId, Authentication auth) {
        if (isPlayer(auth) && !playerId.equals(getPlayerId(auth))) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(reportService.getResultsByPlayer(playerId));
    }

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }

    @SuppressWarnings("unchecked")
    private Long getPlayerId(Authentication auth) {
        if (auth != null && auth.getDetails() instanceof Map<?, ?> details) {
            Object v = details.get("playerId");
            if (v instanceof Long l) return l;
            if (v instanceof Number n) return n.longValue();
        }
        return -1L;
    }

    @GetMapping("/admin/hands-report")
    public ResponseEntity<List<Map<String, Object>>> handsReport(
            @RequestParam String dateFrom,
            @RequestParam String dateTo,
            @RequestParam(defaultValue = "0") int minHands,
            Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        LocalDateTime from = LocalDate.parse(dateFrom).atStartOfDay();
        LocalDateTime to = LocalDate.parse(dateTo).plusDays(1).atStartOfDay();
        List<PlayerHandsProjection> rows = gameResultRepository.getHandsReport(from, to, minHands);
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerHandsProjection r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("playerId", r.getPlayerId());
            m.put("username", r.getUsername());
            m.put("fullName", r.getFullName());
            m.put("totalHands", r.getTotalHands());
            m.put("sessionCount", r.getSessionCount());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadReport(@PathVariable Long id) {
        return reportRepository.findById(id)
            .filter(r -> r.getFilePath() != null && new File(r.getFilePath()).exists())
            .map(r -> {
                Resource resource = new FileSystemResource(r.getFilePath());
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + r.getFileName() + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(resource);
            })
            .orElse(ResponseEntity.notFound().<Resource>build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteReport(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        try {
            reportService.deleteReport(id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> deleteAllReports() {
        long results = gameResultRepository.count();
        long sessions = gameSessionRepository.count();
        long reports = reportRepository.count();
        gameResultRepository.deleteAll();
        gameSessionRepository.deleteAll();
        reportRepository.deleteAll();
        return ResponseEntity.ok(Map.of("deletedResults", results, "deletedSessions", sessions, "deletedReports", reports));
    }
}
