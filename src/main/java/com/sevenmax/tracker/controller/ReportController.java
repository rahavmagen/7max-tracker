package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.GameResult;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Report;
import com.sevenmax.tracker.repository.AdminExpenseRepository;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.GameSessionRepository;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.repository.PlayerHandsProjection;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.ReportRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import com.sevenmax.tracker.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private final ImportSummaryRepository importSummaryRepository;
    private final TransactionRepository transactionRepository;
    private final PlayerRepository playerRepository;
    private final AdminExpenseRepository adminExpenseRepository;

    private static final String UPLOAD_API_KEY = "sevenmax-auto-2026-xK9p";

    @PostMapping("/upload-auto")
    public ResponseEntity<?> uploadReportAuto(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        if (!UPLOAD_API_KEY.equals(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid API key"));
        }
        try {
            Report report = reportService.uploadReport(file, null);
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Upload failed"));
        }
    }

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
                m.put("entryFee", s.getEntryFee());
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

    @GetMapping("/admin/friday-rake")
    public ResponseEntity<List<Map<String, Object>>> fridayRakeReport(Authentication auth) {
        List<Object[]> rows = gameResultRepository.getFridayRakeReport();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", r[0]);
            m.put("startTime", r[1] != null ? r[1].toString() : null);
            m.put("endTime", r[2] != null ? r[2].toString() : null);
            m.put("tableName", r[3]);
            m.put("gameType", r[4]);
            m.put("playerCount", r[5]);
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

    @GetMapping("/admin/chip-balance")
    public ResponseEntity<Map<String, Object>> chipBalance(
            @RequestParam(required = false) String since,
            Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        Map<String, Object> result = new LinkedHashMap<>();
        var summary = importSummaryRepository.findById(1L).orElse(null);
        java.time.LocalDate lastReportDate = summary != null ? summary.getLastReportDate() : null;
        java.math.BigDecimal baseChips = summary != null && summary.getLastReportChipsTotal() != null
            ? summary.getLastReportChipsTotal() : java.math.BigDecimal.ZERO;

        // If caller overrides the cutoff date, find the chips total from the nearest report at/before that date
        java.time.LocalDate sinceDate = since != null ? java.time.LocalDate.parse(since) : null;
        if (sinceDate != null && !sinceDate.equals(lastReportDate)) {
            final java.time.LocalDate sd = sinceDate;
            reportRepository.findAll().stream()
                .filter(r -> r.getPeriodEnd() != null && !r.getPeriodEnd().isAfter(sd) && r.getChipsTotal() != null)
                .max(java.util.Comparator.comparing(Report::getPeriodEnd))
                .ifPresent(r -> {});  // just a check — we'll use sinceDate as cutoff below
            // Find base chips from the report at that date
            baseChips = reportRepository.findAll().stream()
                .filter(r -> r.getPeriodEnd() != null && !r.getPeriodEnd().isAfter(sd) && r.getChipsTotal() != null)
                .max(java.util.Comparator.comparing(Report::getPeriodEnd))
                .map(Report::getChipsTotal)
                .orElse(java.math.BigDecimal.ZERO);
            lastReportDate = sinceDate;
        }

        result.put("lastReportDate", lastReportDate != null ? lastReportDate.toString() : null);
        result.put("baseChips", baseChips);

        if (lastReportDate != null) {
            java.time.LocalDateTime cutoff = lastReportDate.atStartOfDay();
            java.math.BigDecimal deposits = transactionRepository.sumDepositsSince(cutoff);
            java.math.BigDecimal credits = transactionRepository.sumCreditsSince(cutoff);
            java.math.BigDecimal wheel = transactionRepository.sumWheelExpensesSince(cutoff);
            java.math.BigDecimal rake = gameResultRepository.sumRakeSince(cutoff);
            deposits = deposits != null ? deposits : java.math.BigDecimal.ZERO;
            credits = credits != null ? credits : java.math.BigDecimal.ZERO;
            wheel = wheel != null ? wheel : java.math.BigDecimal.ZERO;
            rake = rake != null ? rake : java.math.BigDecimal.ZERO;
            java.math.BigDecimal expected = baseChips.add(deposits).add(credits).subtract(wheel).add(rake);
            java.math.BigDecimal actual = playerRepository.findAll().stream()
                .map(p -> p.getCurrentChips() != null ? p.getCurrentChips() : java.math.BigDecimal.ZERO)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            result.put("deposits", deposits);
            result.put("credits", credits);
            result.put("wheelExpenses", wheel);
            result.put("rake", rake);
            result.put("expectedChips", expected);
            result.put("actualChips", actual);
            result.put("mismatch", expected.subtract(actual).abs());
        } else {
            java.math.BigDecimal actual = playerRepository.findAll().stream()
                .map(p -> p.getCurrentChips() != null ? p.getCurrentChips() : java.math.BigDecimal.ZERO)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            result.put("deposits", java.math.BigDecimal.ZERO);
            result.put("credits", java.math.BigDecimal.ZERO);
            result.put("wheelExpenses", java.math.BigDecimal.ZERO);
            result.put("rake", java.math.BigDecimal.ZERO);
            result.put("expectedChips", actual);
            result.put("actualChips", actual);
            result.put("mismatch", java.math.BigDecimal.ZERO);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/admin/player-validation")
    public ResponseEntity<List<Map<String, Object>>> playerValidation(
            @RequestParam(required = false) String since,
            Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        var summary = importSummaryRepository.findById(1L).orElse(null);
        java.time.LocalDate lastReportDate = since != null
            ? java.time.LocalDate.parse(since)
            : (summary != null ? summary.getLastReportDate() : null);
        java.time.LocalDateTime cutoff = lastReportDate != null
            ? lastReportDate.atStartOfDay()
            : java.time.LocalDate.of(2000, 1, 1).atStartOfDay();

        // Build per-player maps
        Map<Long, java.math.BigDecimal> depositsMap = new HashMap<>();
        for (Object[] r : transactionRepository.getDepositsPerPlayerSince(cutoff))
            depositsMap.put(((Number) r[0]).longValue(), new java.math.BigDecimal(r[1].toString()));
        Map<Long, java.math.BigDecimal> creditsMap = new HashMap<>();
        for (Object[] r : transactionRepository.getCreditsPerPlayerSince(cutoff))
            creditsMap.put(((Number) r[0]).longValue(), new java.math.BigDecimal(r[1].toString()));
        Map<Long, java.math.BigDecimal> wheelMap = new HashMap<>();
        for (Object[] r : transactionRepository.getWheelExpensesPerPlayerSince(cutoff))
            wheelMap.put(((Number) r[0]).longValue(), new java.math.BigDecimal(r[1].toString()));
        Map<Long, java.math.BigDecimal> rakeMap = new HashMap<>();
        for (Object[] r : gameResultRepository.getRakePerPlayerSince(cutoff))
            rakeMap.put(((Number) r[0]).longValue(), new java.math.BigDecimal(r[1].toString()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Player p : playerRepository.findAll()) {
            java.math.BigDecimal chips = p.getCurrentChips() != null ? p.getCurrentChips() : java.math.BigDecimal.ZERO;
            java.math.BigDecimal dep = depositsMap.getOrDefault(p.getId(), java.math.BigDecimal.ZERO);
            java.math.BigDecimal cred = creditsMap.getOrDefault(p.getId(), java.math.BigDecimal.ZERO);
            java.math.BigDecimal whl = wheelMap.getOrDefault(p.getId(), java.math.BigDecimal.ZERO);
            java.math.BigDecimal rake = rakeMap.getOrDefault(p.getId(), java.math.BigDecimal.ZERO);
            // expected = chips(already includes credits+wheel given since last report) + deposits - rake
            java.math.BigDecimal expected = chips.add(dep).subtract(rake);
            java.math.BigDecimal diff = expected.subtract(chips);
            if (diff.compareTo(java.math.BigDecimal.ZERO) == 0 && dep.compareTo(java.math.BigDecimal.ZERO) == 0
                    && cred.compareTo(java.math.BigDecimal.ZERO) == 0 && whl.compareTo(java.math.BigDecimal.ZERO) == 0
                    && rake.compareTo(java.math.BigDecimal.ZERO) == 0) continue; // skip players with no activity
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("playerId", p.getId());
            m.put("username", p.getUsername());
            m.put("fullName", p.getFullName());
            m.put("currentChips", chips);
            m.put("deposits", dep);
            m.put("credits", cred);
            m.put("wheelReceived", whl);
            m.put("rakePaid", rake);
            m.put("expectedNextXls", expected);
            m.put("diff", diff);
            result.add(m);
        }
        result.sort((a, b) -> ((java.math.BigDecimal) b.get("diff")).abs().compareTo(((java.math.BigDecimal) a.get("diff")).abs()));
        return ResponseEntity.ok(result);
    }

    /** Backfill chipsTotal for all reports that have a saved file but no chipsTotal yet */
    @PostMapping("/admin/backfill-chips-total")
    public ResponseEntity<Map<String, Object>> backfillChipsTotal(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        int updated = 0, skipped = 0, failed = 0;
        for (Report report : reportRepository.findAll()) {
            if (report.getChipsTotal() != null) { skipped++; continue; }
            byte[] bytes = null;
            if (report.getFileData() != null && report.getFileData().length > 0) {
                bytes = report.getFileData();
            } else if (report.getFilePath() != null && new File(report.getFilePath()).exists()) {
                try { bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(report.getFilePath())); } catch (Exception ignored) {}
            }
            if (bytes == null) { failed++; continue; }
            try (java.io.InputStream fis = new java.io.ByteArrayInputStream(bytes);
                 org.apache.poi.ss.usermodel.Workbook wb = new XSSFWorkbook(fis)) {
                org.apache.poi.ss.usermodel.Sheet balanceSheet = null;
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    if (wb.getSheetAt(i).getSheetName().toLowerCase().contains("club member balance")) {
                        balanceSheet = wb.getSheetAt(i);
                        break;
                    }
                }
                if (balanceSheet == null) { failed++; continue; }
                // Find nickname/balance columns
                int nicknameCol = -1, balanceCol = -1, headerRow = -1;
                for (int r = 0; r <= Math.min(balanceSheet.getLastRowNum(), 10); r++) {
                    org.apache.poi.ss.usermodel.Row row = balanceSheet.getRow(r);
                    if (row == null) continue;
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                        if (cell == null) continue;
                        String val = cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                            ? cell.getStringCellValue().toLowerCase().trim() : "";
                        if (val.contains("nickname") || val.equals("name")) nicknameCol = c;
                        if (val.contains("balance") || val.contains("chips")) balanceCol = c;
                    }
                    if (nicknameCol >= 0 && balanceCol >= 0) { headerRow = r; break; }
                }
                if (nicknameCol < 0 || balanceCol < 0) { failed++; continue; }
                java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                for (int r = headerRow + 1; r <= balanceSheet.getLastRowNum(); r++) {
                    org.apache.poi.ss.usermodel.Row row = balanceSheet.getRow(r);
                    if (row == null) continue;
                    org.apache.poi.ss.usermodel.Cell nc = row.getCell(nicknameCol);
                    if (nc == null || nc.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING
                            || nc.getStringCellValue().isBlank()) continue;
                    org.apache.poi.ss.usermodel.Cell bc = row.getCell(balanceCol);
                    if (bc == null) continue;
                    try {
                        double v = bc.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            ? bc.getNumericCellValue()
                            : Double.parseDouble(bc.getStringCellValue().replace(",", "").trim());
                        total = total.add(java.math.BigDecimal.valueOf(v));
                    } catch (Exception ignored) {}
                }
                report.setChipsTotal(total);
                reportRepository.save(report);
                updated++;
            } catch (Exception e) {
                failed++;
            }
        }
        // After backfill, refresh ImportSummary to pick up the new chip totals
        reportRepository.findAll().stream()
            .filter(r -> r.getPeriodEnd() != null && r.getChipsTotal() != null)
            .max(java.util.Comparator.comparing(Report::getPeriodEnd))
            .ifPresent(latest -> {
                var summary = importSummaryRepository.findById(1L).orElse(new com.sevenmax.tracker.entity.ImportSummary());
                summary.setId(1L);
                summary.setLastReportChipsTotal(latest.getChipsTotal());
                summary.setLastReportDate(latest.getPeriodEnd());
                importSummaryRepository.save(summary);
            });
        return ResponseEntity.ok(Map.of("updated", updated, "skipped", skipped, "failed", failed));
    }

    @GetMapping("/balance-sheet")
    public ResponseEntity<Map<String, Object>> balanceSheet(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();

        Map<String, Object> result = new LinkedHashMap<>();

        // --- SNAPSHOT ---
        java.math.BigDecimal bankDeposits = transactionRepository.sumAllBankDeposits();
        java.math.BigDecimal creditsGiven = transactionRepository.sumAllCreditsGiven();
        java.math.BigDecimal creditWithdrawals = transactionRepository.sumAllCreditWithdrawals();
        java.math.BigDecimal openCredits = creditsGiven.subtract(creditWithdrawals);

        List<Report> allReports = reportRepository.findAll();
        java.util.Optional<Report> latestReport = allReports.stream()
            .filter(r -> r.getPeriodEnd() != null && r.getChipsTotal() != null)
            .max(java.util.Comparator.comparing(Report::getPeriodEnd));

        java.math.BigDecimal activeChips = latestReport.map(Report::getChipsTotal).orElse(java.math.BigDecimal.ZERO);
        String chipsAsOf = latestReport.map(r -> r.getPeriodEnd().toString()).orElse(null);

        java.math.BigDecimal grossRake = bankDeposits.add(openCredits).subtract(activeChips);
        java.math.BigDecimal totalExpenses = adminExpenseRepository.sumAllExpenses();
        java.math.BigDecimal snapshotNetProfit = grossRake.subtract(totalExpenses);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bankDeposits", bankDeposits);
        snapshot.put("openCredits", openCredits);
        snapshot.put("activeChips", activeChips);
        snapshot.put("chipsAsOf", chipsAsOf);
        snapshot.put("grossRake", grossRake);
        snapshot.put("totalExpenses", totalExpenses);
        snapshot.put("netProfit", snapshotNetProfit);
        result.put("snapshot", snapshot);

        // --- PERIOD ---
        if (from != null && to != null) {
            java.time.LocalDate fromDate = java.time.LocalDate.parse(from);
            java.time.LocalDate toDate = java.time.LocalDate.parse(to);
            java.time.LocalDateTime fromDt = fromDate.atStartOfDay();
            java.time.LocalDateTime toDt = toDate.plusDays(1).atStartOfDay();

            java.math.BigDecimal deposits = transactionRepository.sumBankDepositsBetween(fromDt, toDt);
            java.math.BigDecimal creditsGivenPeriod = transactionRepository.sumCreditsGivenBetween(fromDt, toDt);
            java.math.BigDecimal creditWithdrawalsPeriod = transactionRepository.sumCreditWithdrawalsBetween(fromDt, toDt);
            java.math.BigDecimal netCreditChange = creditsGivenPeriod.subtract(creditWithdrawalsPeriod);

            java.util.Optional<Report> startReport = allReports.stream()
                .filter(r -> r.getPeriodEnd() != null && r.getChipsTotal() != null && !r.getPeriodEnd().isAfter(fromDate))
                .max(java.util.Comparator.comparing(Report::getPeriodEnd));
            java.util.Optional<Report> endReport = allReports.stream()
                .filter(r -> r.getPeriodEnd() != null && r.getChipsTotal() != null && !r.getPeriodEnd().isAfter(toDate))
                .max(java.util.Comparator.comparing(Report::getPeriodEnd));

            java.math.BigDecimal chipsStart = startReport.map(Report::getChipsTotal).orElse(java.math.BigDecimal.ZERO);
            String chipsStartDate = startReport.map(r -> r.getPeriodEnd().toString()).orElse(null);
            java.math.BigDecimal chipsEnd = endReport.map(Report::getChipsTotal).orElse(java.math.BigDecimal.ZERO);
            String chipsEndDate = endReport.map(r -> r.getPeriodEnd().toString()).orElse(null);

            java.math.BigDecimal chipDelta = chipsEnd.subtract(chipsStart);
            java.math.BigDecimal periodRake = deposits.add(netCreditChange).subtract(chipDelta);
            java.math.BigDecimal periodExpenses = adminExpenseRepository.sumExpensesBetween(fromDate, toDate);
            java.math.BigDecimal periodNetProfit = periodRake.subtract(periodExpenses);

            Map<String, Object> period = new LinkedHashMap<>();
            period.put("from", from);
            period.put("to", to);
            period.put("deposits", deposits);
            period.put("netCreditChange", netCreditChange);
            period.put("chipsStart", chipsStart);
            period.put("chipsStartDate", chipsStartDate);
            period.put("chipsEnd", chipsEnd);
            period.put("chipsEndDate", chipsEndDate);
            period.put("chipDelta", chipDelta);
            period.put("periodRake", periodRake);
            period.put("expenses", periodExpenses);
            period.put("netProfit", periodNetProfit);
            result.put("period", period);
        } else {
            result.put("period", null);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadReport(@PathVariable Long id) {
        return reportRepository.findById(id).map(r -> {
            byte[] data = null;
            // Prefer in-memory DB bytes (works on cloud)
            if (r.getFileData() != null && r.getFileData().length > 0) {
                data = r.getFileData();
            } else if (r.getFilePath() != null && new File(r.getFilePath()).exists()) {
                try { data = Files.readAllBytes(Paths.get(r.getFilePath())); } catch (Exception ignored) {}
            }
            if (data == null) return ResponseEntity.notFound().<Resource>build();
            Resource resource = new org.springframework.core.io.ByteArrayResource(data);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + r.getFileName() + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
        }).orElse(ResponseEntity.notFound().<Resource>build());
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
