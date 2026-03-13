package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final GameSessionRepository gameSessionRepository;
    private final GameResultRepository gameResultRepository;
    private final PlayerRepository playerRepository;

    @Transactional
    public Report uploadReport(MultipartFile file, User uploadedBy) throws Exception {
        byte[] fileBytes = file.getBytes();

        try (InputStream is = new java.io.ByteArrayInputStream(fileBytes);
             Workbook workbook = new XSSFWorkbook(is)) {

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

            // Parse Ring Game Detail sheets
            BigDecimal totalRake = BigDecimal.ZERO;
            totalRake = totalRake.add(parseRingGameDetail(workbook, report));
            totalRake = totalRake.add(parseMttDetail(workbook, report));

            report.setTotalRake(totalRake);
            return reportRepository.save(report);
        }
    }

    private BigDecimal parseRingGameDetail(Workbook workbook, Report report) {
        Sheet sheet = workbook.getSheet("Ring Game Detail");
        if (sheet == null) return BigDecimal.ZERO;

        BigDecimal totalRake = BigDecimal.ZERO;
        GameSession currentSession = null;
        int lastRow = sheet.getLastRowNum();

        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String firstCell = getCellValue(row, 0);
            if (firstCell == null || firstCell.isBlank()) continue;

            // Detect session header: "Start/End Time : ..."
            if (firstCell.startsWith("Start/End Time")) {
                currentSession = new GameSession();
                currentSession.setReport(report);
                parseSessionHeader(sheet, r, currentSession);
                currentSession = gameSessionRepository.save(currentSession);
                r += 3; // skip table info, header rows
                continue;
            }

            // Skip total row
            if (firstCell.equals("Total")) continue;

            // Player result row: first cell is club player ID like "2163-3811"
            if (currentSession != null && firstCell.matches("\\d{4}-\\d{4}")) {
                String clubPlayerId = firstCell;
                String nickname = getCellValue(row, 1);
                BigDecimal buyIn = parseBigDecimal(getCellValue(row, 2));
                BigDecimal cashout = parseBigDecimal(getCellValue(row, 3));
                int hands = parseInteger(getCellValue(row, 4));
                BigDecimal rake = parseBigDecimal(getCellValue(row, 10));
                BigDecimal pnl = parseBigDecimal(getCellValue(row, 11));

                // Find player by clubPlayerId, then by username, else create new
                Player player = playerRepository.findByClubPlayerId(clubPlayerId)
                        .or(() -> playerRepository.findByUsername(nickname))
                        .orElseGet(() -> {
                            Player p = new Player();
                            p.setClubPlayerId(clubPlayerId);
                            p.setUsername(nickname);
                            p.setFullName(nickname);
                            return playerRepository.save(p);
                        });
                // Always ensure clubPlayerId is set
                if (player.getClubPlayerId() == null || player.getClubPlayerId().isBlank()) {
                    player.setClubPlayerId(clubPlayerId);
                    playerRepository.save(player);
                }

                GameResult result = new GameResult();
                result.setSession(currentSession);
                result.setPlayer(player);
                result.setBuyIn(buyIn);
                result.setCashout(cashout);
                result.setHandsPlayed(hands);
                result.setRakePaid(rake);
                result.setResultAmount(pnl);
                gameResultRepository.save(result);

                // Update player balance with game result
                player.setBalance(player.getBalance().add(pnl));
                playerRepository.save(player);

                totalRake = totalRake.add(rake);
            }
        }
        return totalRake;
    }

    private BigDecimal parseMttDetail(Workbook workbook, Report report) {
        Sheet sheet = workbook.getSheet("MTT Detail");
        if (sheet == null) return BigDecimal.ZERO;

        BigDecimal totalRake = BigDecimal.ZERO;
        GameSession currentSession = null;
        int lastRow = sheet.getLastRowNum();

        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String firstCell = getCellValue(row, 0);
            if (firstCell == null || firstCell.isBlank()) continue;

            if (firstCell.startsWith("Start/End Time")) {
                currentSession = new GameSession();
                currentSession.setReport(report);
                parseMttSessionHeader(sheet, r, currentSession);
                currentSession = gameSessionRepository.save(currentSession);
                r += 5;
                continue;
            }

            if (firstCell.equals("Total")) continue;

            if (currentSession != null && firstCell.matches("\\d{4}-\\d{4}")) {
                String clubPlayerId = firstCell;
                String nickname = getCellValue(row, 1);
                BigDecimal buyIn = parseBigDecimal(getCellValue(row, 2)).add(parseBigDecimal(getCellValue(row, 6)));
                BigDecimal prize = parseBigDecimal(getCellValue(row, 12));
                int hands = parseInteger(getCellValue(row, 10));
                BigDecimal pnl = parseBigDecimal(getCellValue(row, 14));

                Player player = playerRepository.findByClubPlayerId(clubPlayerId)
                        .or(() -> playerRepository.findByUsername(nickname))
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

                GameResult result = new GameResult();
                result.setSession(currentSession);
                result.setPlayer(player);
                result.setBuyIn(buyIn);
                result.setCashout(prize);
                result.setHandsPlayed(hands);
                result.setRakePaid(BigDecimal.ZERO);
                result.setResultAmount(pnl);
                gameResultRepository.save(result);

                player.setBalance(player.getBalance().add(pnl));
                playerRepository.save(player);
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
                // MTT(NLH) -> MTT, MTT(PLO5) -> MTT, etc.
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
        // "Start/End Time : 2026-03-12 22:18:39 ~ 2026-03-13 06:50:46 ..."
        try {
            String[] parts = timeInfo.split(":");
            if (parts.length >= 3) {
                String datePart = (parts[1] + ":" + parts[2] + ":" + parts[3]).trim();
                String[] range = datePart.split("~");
                session.setStartTime(LocalDateTime.parse(range[0].trim().substring(0, 19)
                        .replace(" ", "T")));
                if (range.length > 1 && !range[1].contains("Not Ended")) {
                    session.setEndTime(LocalDateTime.parse(range[1].trim().substring(0, 19)
                            .replace(" ", "T")));
                }
            }
        } catch (Exception ignored) {}

        // Table info row: "Table Name : 1-2 שאק , Creator : ..."
        Row tableRow = sheet.getRow(headerRow + 1);
        if (tableRow != null) {
            String tableInfo = getCellValue(tableRow, 0);
            if (tableInfo != null && tableInfo.contains("Table Name")) {
                String[] parts = tableInfo.split(",");
                String tableName = parts[0].replace("Table Name :", "").trim();
                session.setTableName(tableName);
            }
        }

        // Game type row: "Table Information : Game : NLH , ..."
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
        // "Period : 2026-03-13 ~ 2026-03-13 (UTC +2:00)"
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

    public List<Report> getAllReports() {
        return reportRepository.findAllByOrderByUploadedAtDesc();
    }

    public List<GameResult> getResultsByPlayer(Long playerId) {
        return gameResultRepository.findByPlayerIdOrderBySessionStartTimeDesc(playerId);
    }
}
