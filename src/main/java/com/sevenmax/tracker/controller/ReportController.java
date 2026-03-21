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
import java.math.BigDecimal;
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

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> getSessions(Authentication auth) {
        List<Map<String, Object>> result = new ArrayList<>();
        gameSessionRepository.findAll().stream()
            .filter(s -> s.getGameType() == com.sevenmax.tracker.entity.GameSession.GameType.MTT
                      || s.getGameType() == com.sevenmax.tracker.entity.GameSession.GameType.SNG)
            .sorted((a, b) -> b.getStartTime() != null && a.getStartTime() != null
                ? b.getStartTime().compareTo(a.getStartTime()) : 0)
            .forEach(s -> {
                List<GameResult> sessionResults = gameResultRepository.findBySessionId(s.getId());
                long playerCount = sessionResults.size();
                int entries = s.getEntryCount() != null ? s.getEntryCount() : (int) playerCount;
                BigDecimal rakeTotal = sessionResults.stream()
                    .map(r -> r.getRakePaid() != null ? r.getRakePaid() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("tableName", s.getTableName());
                m.put("gameType", s.getGameType());
                m.put("startTime", s.getStartTime() != null ? s.getStartTime().toString() : null);
                m.put("endTime", s.getEndTime() != null ? s.getEndTime().toString() : null);
                m.put("rakeTotal", rakeTotal);
                m.put("playerCount", playerCount);
                m.put("entryCount", entries);
                m.put("reEntryCount", entries - (int) playerCount);
                result.add(m);
            });
        return ResponseEntity.ok(result);
    }

    @GetMapping("/sessions/{id}/results")
    public ResponseEntity<List<Map<String, Object>>> getSessionResults(@PathVariable Long id) {
        List<Map<String, Object>> result = new ArrayList<>();
        gameResultRepository.findBySessionId(id).stream()
            .sorted((a, b) -> {
                if (a.getTournamentPlace() != null && b.getTournamentPlace() != null)
                    return Integer.compare(a.getTournamentPlace(), b.getTournamentPlace());
                if (a.getTournamentPlace() != null) return -1;
                if (b.getTournamentPlace() != null) return 1;
                return 0;
            })
            .forEach(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("playerId", r.getPlayer().getId());
                m.put("username", r.getPlayer().getUsername());
                m.put("fullName", r.getPlayer().getFullName());
                m.put("buyIn", r.getBuyIn());
                m.put("cashout", r.getCashout());
                m.put("rakePaid", r.getRakePaid());
                m.put("resultAmount", r.getResultAmount());
                m.put("tournamentPlace", r.getTournamentPlace());
                result.add(m);
            });
        return ResponseEntity.ok(result);
    }

    @GetMapping("/admin/income")
    public ResponseEntity<List<Map<String, Object>>> incomeReport(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        LocalDateTime from = dateFrom != null ? LocalDate.parse(dateFrom).atStartOfDay() : LocalDate.of(2000, 1, 1).atStartOfDay();
        LocalDateTime to = dateTo != null ? LocalDate.parse(dateTo).plusDays(1).atStartOfDay() : LocalDate.of(2100, 1, 1).atStartOfDay();
        List<Object[]> rows = gameResultRepository.getIncomeReport(from, to);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", r[0]);
            m.put("startTime", r[1] != null ? r[1].toString() : null);
            m.put("tableName", r[2]);
            m.put("gameType", r[3]);
            m.put("playerCount", r[4]);
            m.put("totalHands", r[5]);
            m.put("totalRake", r[6]);
            result.add(m);
        }
        return ResponseEntity.ok(result);
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
