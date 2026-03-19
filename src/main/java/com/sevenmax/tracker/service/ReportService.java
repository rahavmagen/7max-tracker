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

            // Parse game results
            Map<String, BigDecimal> gamePnlMap = new HashMap<>();
            BigDecimal totalRake = BigDecimal.ZERO;
            totalRake = totalRake.add(parseRingGameDetail(workbook, report, gamePnlMap));
            totalRake = totalRake.add(parseMttDetail(workbook, report, gamePnlMap));

            // Parse Club Member Balance → newChipsMap (keyed by username.lower)
            Map<String, BigDecimal> newChipsMap = parseClubMemberBalance(memberBalanceSheet);

            // Update player currentChips and balance directly from Club Member Balance
            for (Map.Entry<String, BigDecimal> entry : newChipsMap.entrySet()) {
                String usernameKey = entry.getKey();
                BigDecimal newChips = entry.getValue();
                Player player = playerRepository.findAll().stream()
                        .filter(p -> p.getUsername() != null && p.getUsername().toLowerCase().equals(usernameKey))
                        .findFirst().orElse(null);
                if (player != null) {
                    player.setCurrentChips(newChips);
                    BigDecimal credit = player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO;
                    player.setBalance(newChips.subtract(credit));
                    player.setChipsAsOf(report.getPeriodEnd());
                    playerRepository.save(player);
                    log.debug("Updated player {}: chips={} balance={}", player.getUsername(), newChips, player.getBalance());
                }
            }

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

            playerRepository.findByUsernameCaseInsensitive(username).stream().findFirst().ifPresent(player -> {
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
            if (transactionRepository.existsBySourceRef(sourceRef)) continue;

            // Find player
            Player player = null;
            if (clubPlayerId != null && !clubPlayerId.isBlank() && !clubPlayerId.equals("-")) {
                player = playerRepository.findByClubPlayerId(clubPlayerId).orElse(null);
            }
            if (player == null && nickname != null && !nickname.isBlank()) {
                player = playerRepository.findByUsernameCaseInsensitive(nickname).stream().findFirst().orElse(null);
            }
            if (player == null) continue;

            // Parse date (format: "2026-03-08 11:02:59")
            LocalDate txDate;
            try {
                txDate = LocalDate.parse(dateStr.substring(0, 10));
            } catch (Exception e) {
                txDate = LocalDate.now();
            }

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

    private Map<String, BigDecimal> parseClubMemberBalance(Sheet sheet) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (sheet == null) return map;

        // Find header row to locate Nickname and Balance columns
        int nicknameCol = -1;
        int balanceCol = -1;
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

        log.debug("Club Member Balance: nicknameCol={} balanceCol={} headerRow={}", nicknameCol, balanceCol, headerRowIdx);

        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String nickname = getCellValue(row, nicknameCol);
            if (nickname == null || nickname.isBlank()) continue;
            String balStr = getCellValue(row, balanceCol);
            BigDecimal chips = parseBigDecimal(balStr);
            map.put(nickname.toLowerCase(), chips);
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

    private BigDecimal parseRingGameDetail(Workbook workbook, Report report, Map<String, BigDecimal> gamePnlMap) {
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

                Player player = playerRepository.findByClubPlayerId(clubPlayerId)
                        .or(() -> playerRepository.findByUsernameCaseInsensitive(nickname).stream().findFirst())
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
                }

                totalRake = totalRake.add(rake);
            }
        }
        return totalRake;
    }

    private BigDecimal parseMttDetail(Workbook workbook, Report report, Map<String, BigDecimal> gamePnlMap) {
        Sheet sheet = workbook.getSheet("MTT Detail");
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
                BigDecimal rake = parseBigDecimal(getCellValue(row, 4))   // initial fee chips (col E)
                        .add(parseBigDecimal(getCellValue(row, 5)))            // initial fee ticket (col F)
                        .add(parseBigDecimal(getCellValue(row, 8)))            // re-entry fee chips (col I)
                        .add(parseBigDecimal(getCellValue(row, 9)));           // re-entry fee ticket (col J)
                BigDecimal buyIn = parseBigDecimal(getCellValue(row, 2))  // initial buy-in chips
                        .add(parseBigDecimal(getCellValue(row, 3)))            // initial buy-in ticket
                        .add(rake)
                        .add(parseBigDecimal(getCellValue(row, 6)))            // re-entry chips
                        .add(parseBigDecimal(getCellValue(row, 7)));           // re-entry ticket
                BigDecimal prize = parseBigDecimal(getCellValue(row, 12));
                int hands = parseInteger(getCellValue(row, 10));
                BigDecimal pnl = parseBigDecimal(getCellValue(row, 14));

                Player player = playerRepository.findByClubPlayerId(clubPlayerId)
                        .or(() -> playerRepository.findByUsernameCaseInsensitive(nickname).stream().findFirst())
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
                    result.setCashout(result.getCashout().add(prize));
                    result.setHandsPlayed(result.getHandsPlayed() + hands);
                    result.setRakePaid(result.getRakePaid().add(rake));
                    result.setResultAmount(result.getResultAmount().add(pnl));
                    gameResultRepository.save(result);
                } else {
                    result = new GameResult();
                    result.setSession(currentSession);
                    result.setPlayer(player);
                    result.setBuyIn(buyIn);
                    result.setCashout(prize);
                    result.setHandsPlayed(hands);
                    result.setRakePaid(rake);
                    result.setResultAmount(pnl);
                    gameResultRepository.save(result);
                    resultMap.put(resultKey, result);
                }

                totalRake = totalRake.add(rake);

                if (nickname != null && !nickname.isBlank()) {
                    gamePnlMap.merge(nickname.toLowerCase(), pnl, BigDecimal::add);
                }
            }
        }
        return totalRake;
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
                session.setTableName(tableName);
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
                session.setTableName(tableName);
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
            return new BigDecimal(val.replace(",", ""));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
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
