package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final GameSessionRepository gameSessionRepository;
    private final GameResultRepository gameResultRepository;
    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final PlayerTransferRepository playerTransferRepository;
    private final PlayerService playerService;

    @Transactional(rollbackFor = Exception.class)
    public Report uploadReport(MultipartFile file, User uploadedBy) throws Exception {
        byte[] fileBytes = file.getBytes();

        try (InputStream is = new java.io.ByteArrayInputStream(fileBytes);
             Workbook workbook = new XSSFWorkbook(is)) {

            // Require Club Member Balance tab
            Sheet memberBalanceSheet = findSheet(workbook, "club member balance");
            if (memberBalanceSheet == null) {
                throw new IllegalArgumentException("no member balance tab exists - update the right xls file");
            }

            Report report = new Report();
            report.setUploadedBy(uploadedBy);
            report.setFileName(file.getOriginalFilename());
            report.setUploadedAt(LocalDateTime.now());

            // Parse period from "Club Overview" sheet header
            Sheet overviewSheet = workbook.getSheet("Club Overview");
            if (overviewSheet != null) {
                String period = getCellValue(overviewSheet.getRow(2), 0);
                parsePeriod(period, report);
            }

            report = reportRepository.save(report);

            // Save file to disk for later download
            try {
                Path uploadDir = Paths.get("C:/claude/uploads");
                Files.createDirectories(uploadDir);
                Path dest = uploadDir.resolve(file.getOriginalFilename());
                Files.write(dest, fileBytes);
                report.setFilePath(dest.toString());
            } catch (Exception ignored) {}

            // Parse game results — also build nickname→clubPlayerId map for balance fallback lookup
            Map<String, BigDecimal> gamePnlMap = new HashMap<>();
            Map<String, String> nicknameToClubId = new HashMap<>();
            BigDecimal totalRake = BigDecimal.ZERO;
            totalRake = totalRake.add(parseRingGameDetail(workbook, report, gamePnlMap, nicknameToClubId));
            totalRake = totalRake.add(parseMttDetail(workbook, report, gamePnlMap, nicknameToClubId));
            parseMttStatistics(workbook);

            // Parse Club Member Balance → map of nickname → [chips, clubId]
            Map<String, Object[]> newChipsMap = parseClubMemberBalance(memberBalanceSheet);

            // Only update chips/stale status if this is the most recent report (by periodEnd)
            // Uploading historical XLS files should not overwrite current chip balances
            final Long currentReportId = report.getId();
            java.time.LocalDate latestExistingPeriodEnd = reportRepository.findAll().stream()
                .filter(r -> !r.getId().equals(currentReportId) && r.getPeriodEnd() != null)
                .map(Report::getPeriodEnd)
                .max(java.time.LocalDate::compareTo)
                .orElse(null);
            boolean isLatestReport = latestExistingPeriodEnd == null ||
                (report.getPeriodEnd() != null && !report.getPeriodEnd().isBefore(latestExistingPeriodEnd));
            log.info("Report periodEnd={} latestExisting={} isLatest={}", report.getPeriodEnd(), latestExistingPeriodEnd, isLatestReport);

            // Track which player IDs were updated from XLS
            Set<Long> updatedPlayerIds = new java.util.HashSet<>();

            // Process balance entries: update chips (latest only), recover stale players (always)
            List<Map<String, String>> recovered = new java.util.ArrayList<>();
            for (Map.Entry<String, Object[]> entry : newChipsMap.entrySet()) {
                String nickname = entry.getKey();
                BigDecimal newChips = (BigDecimal) entry.getValue()[0];
                String balanceClubId = (String) entry.getValue()[1];

                Player player = null;
                if (balanceClubId != null) {
                    player = playerRepository.findByClubPlayerIdSafe(balanceClubId).stream().findFirst().orElse(null);
                }
                if (player == null) {
                    String clubId = nicknameToClubId.get(nickname.toLowerCase());
                    if (clubId != null) {
                        player = playerRepository.findByClubPlayerIdSafe(clubId).stream().findFirst().orElse(null);
                    }
                }
                if (player == null) {
                    player = findPlayerByUsername(nickname).orElse(null);
                }

                if (player == null) {
                    if (isLatestReport) {
                        player = new Player();
                        player.setUsername(nickname);
                        player.setFullName(nickname);
                        player.setCurrentChips(newChips);
                        player.setCreditTotal(BigDecimal.ZERO);
                        player.setBalance(newChips.negate());
                        player.setChipsAsOf(report.getPeriodEnd());
                        player.setChipsStale(false);
                        player.setActive(true);
                        if (balanceClubId != null) player.setClubPlayerId(balanceClubId);
                        player = playerRepository.save(player);
                        log.info("Auto-created player from Club Member Balance: {} clubId={}", nickname, balanceClubId);
                    }
                } else {
                    // Set clubPlayerId on existing player if missing
                    if (player.getClubPlayerId() == null && balanceClubId != null) {
                        player.setClubPlayerId(balanceClubId);
                    }
                    boolean wasStale = Boolean.TRUE.equals(player.getChipsStale());
                    if (isLatestReport) {
                        player.setCurrentChips(newChips);
                        BigDecimal credit = player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO;
                        player.setBalance(newChips.subtract(credit));
                        player.setChipsAsOf(report.getPeriodEnd());
                        player.setChipsStale(false);
                        playerRepository.save(player);
                        // If player was stale and now found in latest — they returned
                        if (wasStale) {
                            Map<String, String> info = new java.util.LinkedHashMap<>();
                            info.put("clubPlayerId", player.getClubPlayerId() != null ? player.getClubPlayerId() : "");
                            info.put("username", player.getUsername());
                            recovered.add(info);
                            log.info("Recovered from stale: {} ({})", player.getUsername(), player.getClubPlayerId());
                        }
                    } else {
                        playerRepository.save(player);
                    }
                }
                if (player != null) updatedPlayerIds.add(player.getId());
            }
            report.setRecovered(recovered);

            // For the latest report: mark missing players as stale and collect leftClub
            List<Map<String, String>> leftClub = new java.util.ArrayList<>();
            if (isLatestReport) {
                log.info("STALE LOOP: updatedPlayerIds count={} file={}", updatedPlayerIds.size(), report.getFileName());
                for (Player player : playerRepository.findAll()) {
                    if (!updatedPlayerIds.contains(player.getId())) {
                        boolean wasStale = Boolean.TRUE.equals(player.getChipsStale());
                        player.setChipsStale(true);
                        playerRepository.save(player);
                        log.info("STALE LOOP: player={} id={} wasStale={}", player.getUsername(), player.getId(), wasStale);
                        // Only flag as newly left (not already stale before this upload)
                        if (!wasStale) {
                            Map<String, String> info = new java.util.LinkedHashMap<>();
                            info.put("username", player.getUsername());
                            info.put("fullName", player.getFullName() != null ? player.getFullName() : "");
                            info.put("clubPlayerId", player.getClubPlayerId() != null ? player.getClubPlayerId() : "");
                            info.put("id", String.valueOf(player.getId()));
                            leftClub.add(info);
                            log.warn("LEFT CLUB: {} ({}) id={}", player.getUsername(), player.getClubPlayerId(), player.getId());
                        }
                    }
                }
                log.info("STALE LOOP DONE: leftClub size={} file={}", leftClub.size(), report.getFileName());
            } else {
                log.info("Historical report — skipping chip update, stale marking (periodEnd={} < latest={})", report.getPeriodEnd(), latestExistingPeriodEnd);
            }
            report.setLeftClub(leftClub);

            // Parse מעקב קרדיטים → update player creditTotal if sheet exists
            parseCreditSheet(workbook);

            // Parse Trade Record → create CREDIT/REPAYMENT transactions (skip already-imported)
            parseTradeRecord(workbook);

            report.setTotalRake(totalRake);
            return reportRepository.save(report);
        }
    }

    private void parseCreditSheet(Workbook workbook) {
        Sheet sheet = null;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            String name = workbook.getSheetAt(i).getSheetName();
            if (name.contains("קרדיט") || name.toLowerCase().contains("credit")) {
                sheet = workbook.getSheetAt(i);
                break;
            }
        }
        if (sheet == null) return;

        log.info("Parsing credit sheet: {}", sheet.getSheetName());
        for (int r = 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String username = getCellValue(row, 0);
            if (username == null || username.isBlank()) continue;

            BigDecimal colC = parseBigDecimal(getCellValue(row, 2));
            BigDecimal colD = parseBigDecimal(getCellValue(row, 3));
            BigDecimal colE = parseBigDecimal(getCellValue(row, 4));
            BigDecimal colF = parseBigDecimal(getCellValue(row, 5));
            BigDecimal total = colC.add(colD).add(colE).add(colF);

            findPlayerByUsername(username).ifPresent(player -> {
                player.setCreditTotal(total);
                BigDecimal chips = player.getCurrentChips() != null ? player.getCurrentChips() : BigDecimal.ZERO;
                player.setBalance(chips.subtract(total));
                playerRepository.save(player);
                log.debug("Updated credit for {}: creditTotal={}", player.getUsername(), total);
            });
        }
    }

    private void parseTradeRecord(Workbook workbook) {
        Sheet sheet = workbook.getSheet("Trade Record");
        if (sheet == null) return;

        int lastRow = sheet.getLastRowNum();
        for (int r = 5; r <= lastRow; r++) {  // data starts at row 5
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String dateStr = getCellValue(row, 0);
            String tradeType = getCellValue(row, 4);
            String amountStr = getCellValue(row, 6);
            String clubPlayerId = getCellValue(row, 14);
            String nickname = getCellValue(row, 15);

            if (dateStr == null || dateStr.isBlank()) continue;
            if (tradeType == null || (!tradeType.equals("Send Chips") && !tradeType.equals("Claim Chips"))) continue;

            BigDecimal amount = parseBigDecimal(amountStr).abs();
            if (amount.compareTo(BigDecimal.ZERO) == 0) continue;

            // Dedup key: prevents re-importing the same trade on re-upload
            String sourceRef = "TRADE:" + dateStr + ":" + (clubPlayerId != null ? clubPlayerId : nickname);
            boolean alreadyImported = transactionRepository.existsBySourceRef(sourceRef);

            // Find player
            Player player = null;
            if (clubPlayerId != null && !clubPlayerId.isBlank() && !clubPlayerId.equals("-")) {
                player = playerRepository.findByClubPlayerIdSafe(clubPlayerId).stream().findFirst().orElse(null);
            }
            if (player == null && nickname != null && !nickname.isBlank()) {
                player = findPlayerByUsername(nickname).orElse(null);
            }
            if (player == null) continue;

            // Parse date (format: "2026-03-08 11:02:59")
            LocalDate txDate;
            try {
                txDate = LocalDate.parse(dateStr.substring(0, 10));
            } catch (Exception e) {
                txDate = LocalDate.now();
            }

            // Check for matching pending PlayerTransfer — only if not already imported to avoid double-confirm
            boolean transferConfirmed = false;
            if (!alreadyImported) {
                if (tradeType.equals("Send Chips")) {
                    var matchedTransfer = playerTransferRepository.findFirstByFromPlayerIdAndAmountAndConfirmedFalse(player.getId(), amount);
                    if (matchedTransfer.isPresent()) {
                        PlayerTransfer transfer = matchedTransfer.get();
                        transfer.setConfirmed(true);
                        playerTransferRepository.save(transfer);
                        log.info("XLS matched pending transfer id={} (Send Chips, player={}, amount={})", transfer.getId(), player.getUsername(), amount);
                        transferConfirmed = true;
                    }
                } else {
                    var matchedTransfer = playerTransferRepository.findFirstByToPlayerIdAndAmountAndConfirmedFalse(player.getId(), amount);
                    if (matchedTransfer.isPresent()) {
                        PlayerTransfer transfer = matchedTransfer.get();
                        transfer.setConfirmed(true);
                        playerTransferRepository.save(transfer);
                        log.info("XLS matched pending transfer id={} (Claim Chips, player={}, amount={})", transfer.getId(), player.getUsername(), amount);
                        transferConfirmed = true;
                    }
                }
            }

            // Check for matching pending Transaction (credit/promo) — run even if already imported,
            // so re-uploading an XLS can still confirm a pending promotion/credit
            if (!transferConfirmed) {
                transactionRepository.findFirstByPlayerIdAndAmountAndPendingConfirmationTrue(player.getId(), amount).ifPresent(pendingTx -> {
                    pendingTx.setPendingConfirmation(false);
                    transactionRepository.save(pendingTx);
                    log.info("XLS confirmed pending transaction id={} (player={}, amount={})", pendingTx.getId(), player.getUsername(), amount);
                });
            }

            if (alreadyImported || transferConfirmed) continue;

            Transaction tx = new Transaction();
            tx.setPlayer(player);
            tx.setType(tradeType.equals("Send Chips") ? Transaction.Type.CREDIT : Transaction.Type.REPAYMENT);
            tx.setAmount(amount);
            tx.setTransactionDate(txDate);
            tx.setSourceRef(sourceRef);
            tx.setNotes("Trade Record: " + tradeType);
            tx.setCreatedByUsername("Import");
            transactionRepository.save(tx);
        }
    }

    // Entry: nickname → [chips, clubPlayerId_or_null]
    private Map<String, Object[]> parseClubMemberBalance(Sheet sheet) {
        Map<String, Object[]> map = new HashMap<>();
        if (sheet == null) return map;

        int nicknameCol = -1;
        int balanceCol = -1;
        int clubIdCol = -1;
        int headerRowIdx = -1;

        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String val = getCellValue(row, c);
                if (val == null) continue;
                String lower = val.toLowerCase();
                if (lower.contains("nickname") || lower.equals("name")) nicknameCol = c;
                if (lower.contains("balance") || lower.contains("chips")) balanceCol = c;
                if (lower.contains("member id") || lower.contains("club id") || lower.contains("player id") || lower.equals("id")) clubIdCol = c;
            }
            if (nicknameCol >= 0 && balanceCol >= 0) {
                headerRowIdx = r;
                break;
            }
        }

        if (nicknameCol < 0 || balanceCol < 0) {
            log.warn("Could not find Nickname/Balance columns in Club Member Balance sheet");
            return map;
        }

        log.info("Club Member Balance: nicknameCol={} balanceCol={} clubIdCol={} headerRow={}", nicknameCol, balanceCol, clubIdCol, headerRowIdx);

        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String nickname = getCellValue(row, nicknameCol);
            if (nickname == null || nickname.isBlank()) continue;
            BigDecimal chips = parseBigDecimal(getCellValue(row, balanceCol));
            String clubId = null;
            if (clubIdCol >= 0) {
                String raw = getCellValue(row, clubIdCol);
                if (raw != null && raw.matches("\\d{4}-\\d{4}")) clubId = raw;
                else if (raw != null && raw.replaceAll("[^0-9]", "").length() == 8) {
                    String digits = raw.replaceAll("[^0-9]", "");
                    clubId = digits.substring(0, 4) + "-" + digits.substring(4);
                }
            }
            map.put(nickname, new Object[]{chips, clubId});
        }
        return map;
    }

    private Sheet findSheet(Workbook workbook, String nameLower) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet s = workbook.getSheetAt(i);
            if (s.getSheetName().toLowerCase().contains(nameLower)) {
                return s;
            }
        }
        return null;
    }

    private BigDecimal parseRingGameDetail(Workbook workbook, Report report, Map<String, BigDecimal> gamePnlMap, Map<String, String> nicknameToClubId) {
        Sheet sheet = workbook.getSheet("Ring Game Detail");
        if (sheet == null) return BigDecimal.ZERO;

        BigDecimal totalRake = BigDecimal.ZERO;
        GameSession currentSession = null;
        Map<String, GameSession> sessionMap = new HashMap<>();
        Map<String, GameResult> resultMap = new HashMap<>();
        int lastRow = sheet.getLastRowNum();

        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String firstCell = getCellValue(row, 0);
            if (firstCell == null || firstCell.isBlank()) continue;

            if (firstCell.startsWith("Start/End Time")) {
                GameSession candidate = new GameSession();
                candidate.setReport(report);
                parseSessionHeader(sheet, r, candidate);
                String sessionKey = candidate.getStartTime() + "|" + candidate.getTableName();
                currentSession = sessionMap.get(sessionKey);
                if (currentSession == null) {
                    currentSession = gameSessionRepository.save(candidate);
                    sessionMap.put(sessionKey, currentSession);
                }
                r += 3;
                continue;
            }

            if (firstCell.equals("Total")) continue;

            if (currentSession != null && firstCell.matches("\\d{4}-\\d{4}")) {
                String clubPlayerId = firstCell;
                String nickname = getCellValue(row, 1);
                BigDecimal buyIn = parseBigDecimal(getCellValue(row, 2));
                BigDecimal cashout = parseBigDecimal(getCellValue(row, 3));
                int hands = parseInteger(getCellValue(row, 4));
                BigDecimal rake = parseBigDecimal(getCellValue(row, 10));
                BigDecimal pnl = parseBigDecimal(getCellValue(row, 11));

                Player player = playerRepository.findByClubPlayerIdSafe(clubPlayerId).stream().findFirst()
                        .or(() -> findPlayerByUsername(nickname))
                        .orElseGet(() -> {
                            Player p = new Player();
                            p.setClubPlayerId(clubPlayerId);
                            p.setUsername(nickname);
                            p.setFullName(nickname);
                            return playerRepository.save(p);
                        });
                if (player.getClubPlayerId() == null || player.getClubPlayerId().isBlank()) {
                    player.setClubPlayerId(clubPlayerId);
                    playerRepository.save(player);
                }

                String resultKey = currentSession.getId() + "|" + player.getId();
                GameResult result = resultMap.get(resultKey);
                if (result != null) {
                    result.setBuyIn(result.getBuyIn().add(buyIn));
                    result.setCashout(result.getCashout().add(cashout));
                    result.setHandsPlayed(result.getHandsPlayed() + hands);
                    result.setRakePaid(result.getRakePaid().add(rake));
                    result.setResultAmount(result.getResultAmount().add(pnl));
                    gameResultRepository.save(result);
                } else {
                    result = new GameResult();
                    result.setSession(currentSession);
                    result.setPlayer(player);
                    result.setBuyIn(buyIn);
                    result.setCashout(cashout);
                    result.setHandsPlayed(hands);
                    result.setRakePaid(rake);
                    result.setResultAmount(pnl);
                    gameResultRepository.save(result);
                    resultMap.put(resultKey, result);
                }

                if (nickname != null && !nickname.isBlank()) {
                    gamePnlMap.merge(nickname.toLowerCase(), pnl, BigDecimal::add);
                    nicknameToClubId.put(nickname.toLowerCase(), clubPlayerId);
                }

                totalRake = totalRake.add(rake);
            }
        }
        return totalRake;
    }

    private BigDecimal parseMttDetail(Workbook workbook, Report report, Map<String, BigDecimal> gamePnlMap, Map<String, String> nicknameToClubId) {
        Sheet sheet = workbook.getSheet("MTT Detail");
        if (sheet == null) return BigDecimal.ZERO;

        BigDecimal totalRake = BigDecimal.ZERO;
        GameSession currentSession = null;
        Map<String, GameSession> sessionMap = new HashMap<>();
        Map<String, GameResult> resultMap = new HashMap<>();
        Map<Long, Integer> sessionEntryCounts = new HashMap<>(); // kept for fallback if P3 not present
        Map<Long, Integer> sessionPositions = new HashMap<>();
        int lastRow = sheet.getLastRowNum();

        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String firstCell = getCellValue(row, 0);
            if (firstCell == null || firstCell.isBlank()) continue;

            if (firstCell.startsWith("Start/End Time")) {
                GameSession candidate = new GameSession();
                candidate.setReport(report);
                parseMttSessionHeader(sheet, r, candidate);
                String sessionKey = candidate.getStartTime() + "|" + candidate.getTableName();
                currentSession = sessionMap.get(sessionKey);
                if (currentSession == null) {
                    currentSession = gameSessionRepository.save(candidate);
                    sessionMap.put(sessionKey, currentSession);
                }
                r += 5;
                continue;
            }

            if (firstCell.equals("Total")) continue;

            if (currentSession != null && firstCell.matches("\\d{4}-\\d{4}")) {
                String clubPlayerId = firstCell;
                String nickname = getCellValue(row, 1);
                BigDecimal initialBuyIn = parseBigDecimal(getCellValue(row, 2))
                        .add(parseBigDecimal(getCellValue(row, 3)));           // initial buy-in chips + ticket
                BigDecimal reEntryBuyIn = parseBigDecimal(getCellValue(row, 6))
                        .add(parseBigDecimal(getCellValue(row, 7)));           // re-entry chips + ticket
                // Count entries: 1 initial + however many re-entries (reEntryBuyIn / initialBuyIn)
                int playerEntries = 1;
                if (initialBuyIn.compareTo(BigDecimal.ZERO) > 0 && reEntryBuyIn.compareTo(BigDecimal.ZERO) > 0) {
                    playerEntries += reEntryBuyIn.divide(initialBuyIn, 0, java.math.RoundingMode.HALF_UP).intValue();
                }
                sessionEntryCounts.merge(currentSession.getId(), playerEntries, Integer::sum);
                BigDecimal rake = parseBigDecimal(getCellValue(row, 4))   // initial fee chips (col E)
                        .add(parseBigDecimal(getCellValue(row, 5)))            // initial fee ticket (col F)
                        .add(parseBigDecimal(getCellValue(row, 8)))            // re-entry fee chips (col I)
                        .add(parseBigDecimal(getCellValue(row, 9)));           // re-entry fee ticket (col J)
                BigDecimal buyIn = initialBuyIn
                        .add(rake)
                        .add(reEntryBuyIn);
                BigDecimal prize = parseBigDecimal(getCellValue(row, 11))  // col L
                        .add(parseBigDecimal(getCellValue(row, 12)));           // col M
                int hands = parseInteger(getCellValue(row, 10));
                BigDecimal winnings = parseBigDecimal(getCellValue(row, 14));  // col O

                Player player = playerRepository.findByClubPlayerIdSafe(clubPlayerId).stream().findFirst()
                        .or(() -> findPlayerByUsername(nickname))
                        .orElseGet(() -> {
                            Player p = new Player();
                            p.setClubPlayerId(clubPlayerId);
                            p.setUsername(nickname);
                            p.setFullName(nickname);
                            return playerRepository.save(p);
                        });
                if (player.getClubPlayerId() == null || player.getClubPlayerId().isBlank()) {
                    player.setClubPlayerId(clubPlayerId);
                    playerRepository.save(player);
                }

                String resultKey = currentSession.getId() + "|" + player.getId();
                GameResult result = resultMap.get(resultKey);
                if (result != null) {
                    result.setBuyIn(result.getBuyIn().add(buyIn));
                    result.setCashout(result.getCashout().add(winnings));
                    result.setHandsPlayed(result.getHandsPlayed() + hands);
                    result.setRakePaid(result.getRakePaid().add(rake));
                    result.setResultAmount(result.getResultAmount().add(prize));
                    gameResultRepository.save(result);
                } else {
                    int place = sessionPositions.merge(currentSession.getId(), 1, Integer::sum);
                    result = new GameResult();
                    result.setSession(currentSession);
                    result.setPlayer(player);
                    result.setBuyIn(buyIn);
                    result.setCashout(winnings);
                    result.setHandsPlayed(hands);
                    result.setRakePaid(rake);
                    result.setResultAmount(prize);
                    result.setTournamentPlace(place);
                    gameResultRepository.save(result);
                    resultMap.put(resultKey, result);
                }

                totalRake = totalRake.add(rake);

                if (nickname != null && !nickname.isBlank()) {
                    gamePnlMap.merge(nickname.toLowerCase(), winnings, BigDecimal::add);
                    nicknameToClubId.put(nickname.toLowerCase(), clubPlayerId);
                }
            }
        }
        // Save entry counts as fallback for sessions where P3 was not available
        for (Map.Entry<Long, Integer> e : sessionEntryCounts.entrySet()) {
            gameSessionRepository.findById(e.getKey()).ifPresent(s -> {
                if (s.getEntryCount() == null) {
                    s.setEntryCount(e.getValue());
                    gameSessionRepository.save(s);
                }
            });
        }
        return totalRake;
    }

    private void parseMttStatistics(Workbook workbook) {
        Sheet sheet = workbook.getSheet("MTT Statistics");
        if (sheet == null) { log.warn("MTT Statistics sheet not found"); return; }

        List<GameSession> mttSessions = gameSessionRepository.findAll().stream()
            .filter(s -> s.getGameType() == GameSession.GameType.MTT || s.getGameType() == GameSession.GameType.SNG)
            .toList();

        for (int r = 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            LocalDateTime startTime = null;
            LocalDateTime endTime = null;
            try {
                Cell startCell = row.getCell(12); // M = start time
                if (startCell != null && startCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(startCell))
                    startTime = startCell.getLocalDateTimeCellValue();
                else { String v = getCellValue(row, 12); if (v != null && !v.isBlank()) startTime = LocalDateTime.parse(v.trim().substring(0, 19).replace(" ", "T")); }
            } catch (Exception ignored) {}
            try {
                Cell endCell = row.getCell(13); // N = end time
                if (endCell != null && endCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(endCell))
                    endTime = endCell.getLocalDateTimeCellValue();
                else { String v = getCellValue(row, 13); if (v != null && !v.isBlank()) endTime = LocalDateTime.parse(v.trim().substring(0, 19).replace(" ", "T")); }
            } catch (Exception ignored) {}

            if (startTime == null) continue;
            final LocalDateTime st = startTime;
            final LocalDateTime et = endTime;

            mttSessions.stream()
                .filter(s -> s.getStartTime() != null && s.getStartTime().withSecond(0).withNano(0).equals(st.withSecond(0).withNano(0)))
                .findFirst()
                .ifPresent(s -> {
                    if (et != null) { s.setEndTime(et); gameSessionRepository.save(s); }
                });
        }
    }

    private void parseMttSessionHeader(Sheet sheet, int headerRow, GameSession session) {
        String timeInfo = getCellValue(sheet.getRow(headerRow), 0);
        try {
            String[] parts = timeInfo.split(":");
            if (parts.length >= 3) {
                String datePart = (parts[1] + ":" + parts[2] + ":" + parts[3]).trim();
                String[] range = datePart.split("~");
                session.setStartTime(LocalDateTime.parse(range[0].trim().substring(0, 19).replace(" ", "T")));
                if (range.length > 1 && !range[1].contains("Not Ended")) {
                    session.setEndTime(LocalDateTime.parse(range[1].trim().substring(0, 19).replace(" ", "T")));
                }
            }
        } catch (Exception ignored) {}

        Row tableRow = sheet.getRow(headerRow + 1);
        if (tableRow != null) {
            String tableInfo = getCellValue(tableRow, 0);
            if (tableInfo != null && tableInfo.contains("Table Name")) {
                String[] parts = tableInfo.split(",");
                String tableName = parts[0].replace("Table Name :", "").trim();
                session.setTableName(fixHebrew(tableName));
            }
        }

        Row gameRow = sheet.getRow(headerRow + 2);
        if (gameRow != null) {
            String gameInfo = getCellValue(gameRow, 0);
            if (gameInfo != null && gameInfo.contains("Game :")) {
                String gameTypePart = gameInfo.split("Game :")[1].split(",")[0].trim();
                if (gameTypePart.startsWith("MTT")) {
                    session.setGameType(GameSession.GameType.MTT);
                } else if (gameTypePart.startsWith("SNG")) {
                    session.setGameType(GameSession.GameType.SNG);
                } else {
                    try {
                        session.setGameType(GameSession.GameType.valueOf(gameTypePart));
                    } catch (Exception e) {
                        session.setGameType(GameSession.GameType.MTT);
                    }
                }
            }
        }
    }

    private void parseSessionHeader(Sheet sheet, int headerRow, GameSession session) {
        String timeInfo = getCellValue(sheet.getRow(headerRow), 0);
        try {
            String[] parts = timeInfo.split(":");
            if (parts.length >= 3) {
                String datePart = (parts[1] + ":" + parts[2] + ":" + parts[3]).trim();
                String[] range = datePart.split("~");
                session.setStartTime(LocalDateTime.parse(range[0].trim().substring(0, 19).replace(" ", "T")));
                if (range.length > 1 && !range[1].contains("Not Ended")) {
                    session.setEndTime(LocalDateTime.parse(range[1].trim().substring(0, 19).replace(" ", "T")));
                }
            }
        } catch (Exception ignored) {}

        Row tableRow = sheet.getRow(headerRow + 1);
        if (tableRow != null) {
            String tableInfo = getCellValue(tableRow, 0);
            if (tableInfo != null && tableInfo.contains("Table Name")) {
                String[] parts = tableInfo.split(",");
                String tableName = parts[0].replace("Table Name :", "").trim();
                session.setTableName(fixHebrew(tableName));
            }
        }

        Row gameRow = sheet.getRow(headerRow + 2);
        if (gameRow != null) {
            String gameInfo = getCellValue(gameRow, 0);
            if (gameInfo != null && gameInfo.contains("Game :")) {
                String gameTypePart = gameInfo.split("Game :")[1].split(",")[0].trim();
                try {
                    session.setGameType(GameSession.GameType.valueOf(gameTypePart));
                } catch (Exception e) {
                    session.setGameType(GameSession.GameType.NLH);
                }
            }
        }
    }

    private void parsePeriod(String periodStr, Report report) {
        try {
            if (periodStr != null && periodStr.contains("~")) {
                String[] parts = periodStr.replace("Period :", "").trim().split("~");
                report.setPeriodStart(LocalDate.parse(parts[0].trim()));
                report.setPeriodEnd(LocalDate.parse(parts[1].trim().substring(0, 10)));
            }
        } catch (Exception ignored) {}
    }

    private String fixHebrew(String s) {
        if (s == null) return null;
        for (char c : s.toCharArray()) {
            if (c >= '\u0590' && c <= '\u05FF') {
                return new StringBuilder(s).reverse().toString();
            }
        }
        return s;
    }

    private String getCellValue(Row row, int col) {
        if (row == null) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;
        try {
            // Strip any non-numeric prefix like "(Deal) " — keep digits, dot, minus
            String cleaned = val.replace(",", "").replaceAll("^[^\\d\\-\\.]+", "").trim();
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Optional<Player> findPlayerByUsername(String username) {
        return playerService.findPlayerByUsername(username);
    }

    private int parseInteger(String val) {
        if (val == null || val.isBlank()) return 0;
        try {
            return (int) Double.parseDouble(val);
        } catch (Exception e) {
            return 0;
        }
    }

    @Transactional
    public void deleteReport(Long reportId) {
        List<GameSession> sessions = gameSessionRepository.findByReportId(reportId);
        for (GameSession session : sessions) {
            List<GameResult> results = gameResultRepository.findBySessionId(session.getId());
            for (GameResult result : results) {
                Player player = result.getPlayer();
                player.setCurrentChips(BigDecimal.ZERO);
                BigDecimal credit = player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO;
                player.setBalance(BigDecimal.ZERO.subtract(credit));
                playerRepository.save(player);
            }
            gameResultRepository.deleteAll(results);
        }
        gameSessionRepository.deleteAll(sessions);
        reportRepository.deleteById(reportId);
        log.info("Deleted report {} along with {} sessions", reportId, sessions.size());
    }

    public List<Report> getAllReports() {
        return reportRepository.findAllByOrderByUploadedAtDesc();
    }

    public List<GameResult> getResultsByPlayer(Long playerId) {
        return gameResultRepository.findByPlayerIdOrderBySessionStartTimeDesc(playerId);
    }
}
