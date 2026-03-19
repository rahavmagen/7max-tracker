package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final PlayerRepository playerRepository;
    private final GameResultRepository gameResultRepository;

    /**
     * Import players from max7.xlsx:
     *   Sheet 1 (מעקב יוזרים): username, fullName, phone, clubUserId
     *   Sheet 4 (מעקב קרדיטים): username → creditTotal (sum of cols C+D+E)
     *
     * If clearExisting=true, all existing players are deleted first.
     * currentChips and balance are set via ClubGG report uploads (Upload Report page).
     */
    @Transactional
    public Map<String, Object> importFromFiles(MultipartFile max7File, boolean clearExisting) throws Exception {


        // Step 1: Read max7 - users
        Map<String, Player> playerMap = new HashMap<>();

        try (InputStream is = max7File.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            if (wb.getNumberOfSheets() < 1) {
                throw new Exception("Invalid file: no sheets found");
            }

            // Sheet 1: users (מעקב יוזרים)
            Sheet sheet1 = wb.getSheetAt(0);
            log.info("Reading sheet 1 '{}': {} rows", sheet1.getSheetName(), sheet1.getLastRowNum());
            for (int r = 1; r <= sheet1.getLastRowNum(); r++) {
                Row row = sheet1.getRow(r);
                if (row == null) continue;
                String username = getText(row, 0);
                if (username.isBlank()) continue;

                Player p = new Player();
                p.setUsername(username);
                p.setFullName(getText(row, 1));
                p.setPhone(getText(row, 2));

                String rawId = getText(row, 3);
                if (!rawId.isBlank()) {
                    rawId = rawId.replace(".0", "").trim();
                    if (rawId.length() == 8) {
                        rawId = rawId.substring(0, 4) + "-" + rawId.substring(4);
                    }
                    p.setClubPlayerId(rawId);
                }
                playerMap.put(username.toLowerCase(), p);
            }
            log.info("Found {} players in sheet 1", playerMap.size());

            // Sheet 4: credits (מעקב קרדיטים) - cols: username, name, C=רועי, D=יאיר, E=אורי
            if (wb.getNumberOfSheets() >= 4) {
                Sheet sheet4 = wb.getSheetAt(3);
                log.info("Reading sheet 4 '{}': {} rows", sheet4.getSheetName(), sheet4.getLastRowNum());
                for (int r = 2; r <= sheet4.getLastRowNum(); r++) {
                    Row row = sheet4.getRow(r);
                    if (row == null) continue;
                    String username = getText(row, 0);
                    if (username.isBlank()) continue;

                    BigDecimal colC = parseBD(getText(row, 2));
                    BigDecimal colD = parseBD(getText(row, 3));
                    BigDecimal colE = parseBD(getText(row, 4));
                    BigDecimal colF = parseBD(getText(row, 5));
                    BigDecimal total = colC.add(colD).add(colE).add(colF);

                    Player p = playerMap.get(username.toLowerCase());
                    if (p != null) p.setCreditTotal(total);
                }
            }
        }

        log.info("max7 import done: {} players in map", playerMap.size());

        // Step 2: Calculate balance (P&L) and save
        // Note: currentChips will be 0 until a ClubGG report with Club Member Balance is uploaded
        int created = 0, updated = 0;
        for (Player p : playerMap.values()) {
            if (p.getUsername() == null || p.getUsername().isBlank()) continue;
            if (p.getCurrentChips() == null) p.setCurrentChips(BigDecimal.ZERO);
            if (p.getCreditTotal() == null) p.setCreditTotal(BigDecimal.ZERO);

            BigDecimal pnl = p.getCurrentChips().subtract(p.getCreditTotal());
            p.setBalance(pnl);
            p.setActive(true);

            Optional<Player> existing = playerRepository.findByUsernameIgnoreCase(p.getUsername());
            if (existing.isEmpty() && p.getClubPlayerId() != null && !p.getClubPlayerId().isBlank()) {
                existing = playerRepository.findByClubPlayerId(p.getClubPlayerId());
            }
            if (existing.isPresent()) {
                updated++;
            } else {
                playerRepository.save(p);
                created++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("total", playerMap.size());
        return result;
    }

    private String getText(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v)) yield String.valueOf((long) v);
                yield String.valueOf(v);
            }
            default -> "";
        };
    }

    private BigDecimal parseBD(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(val.replace(",", "").replace(" ", ""));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
