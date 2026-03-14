package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ImportService {

    private final PlayerRepository playerRepository;

    /**
     * Import players from max7.xlsx:
     *   Sheet 1 (מעקב יוזרים): username, fullName, phone, clubUserId
     *   Sheet 4 (מעקב קרדיטים): username → creditTotal (sum of cols C+D+E)
     *
     * Then merge with ClubGG balance file (Club Member Balance tab):
     *   nickname → currentChips
     *
     * balance (P&L) = currentChips - creditTotal
     * For cash players (no credit): balance = currentChips (their deposits)
     */
    @Transactional
    public Map<String, Object> importFromFiles(MultipartFile max7File, MultipartFile balanceFile) throws Exception {

        // Step 1: Read max7 - users
        Map<String, Player> playerMap = new HashMap<>(); // key = username lowercase

        try (InputStream is = max7File.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            // Sheet 1: users
            Sheet sheet1 = wb.getSheetAt(0);
            for (int r = 1; r <= sheet1.getLastRowNum(); r++) {
                Row row = sheet1.getRow(r);
                if (row == null) continue;
                String username = getText(row, 0);
                if (username.isBlank()) continue;

                Player p = new Player();
                p.setUsername(username);
                p.setFullName(getText(row, 1));
                p.setPhone(getText(row, 2));

                // Club user ID: stored as number like 72726933 → format as "7272-6933"
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

            // Sheet 4: credits (cols: username, name, C=רועי, D=יאיר, E=אורי)
            Sheet sheet4 = wb.getSheetAt(3);
            for (int r = 2; r <= sheet4.getLastRowNum(); r++) {
                Row row = sheet4.getRow(r);
                if (row == null) continue;
                String username = getText(row, 0);
                if (username.isBlank()) continue;

                BigDecimal col3 = parseBD(getText(row, 2)); // רועי
                BigDecimal col4 = parseBD(getText(row, 3)); // יאיר
                BigDecimal col5 = parseBD(getText(row, 4)); // אורי
                BigDecimal total = col3.add(col4).add(col5);

                Player p = playerMap.computeIfAbsent(username.toLowerCase(), k -> {
                    Player np = new Player();
                    np.setUsername(username);
                    return np;
                });
                p.setCreditTotal(total);
            }
        }

        // Step 2: Read ClubGG balance file - Club Member Balance tab (optional)
        if (balanceFile != null && !balanceFile.isEmpty())
        try (InputStream is = balanceFile.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {

            Sheet balanceSheet = null;
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                if (wb.getSheetName(s).contains("Member Balance")) {
                    balanceSheet = wb.getSheetAt(s);
                    break;
                }
            }

            if (balanceSheet != null) {
                for (int r = 5; r <= balanceSheet.getLastRowNum(); r++) {
                    Row row = balanceSheet.getRow(r);
                    if (row == null) continue;
                    String nickname = getText(row, 8); // col I = nickname
                    if (nickname.isBlank()) continue;

                    BigDecimal chips = parseBD(getText(row, 9)); // col J = chips

                    // Match by nickname (case-insensitive)
                    Player p = playerMap.get(nickname.toLowerCase());
                    if (p == null) {
                        // Try partial match
                        for (Map.Entry<String, Player> e : playerMap.entrySet()) {
                            if (e.getKey().equalsIgnoreCase(nickname)) {
                                p = e.getValue();
                                break;
                            }
                        }
                    }
                    if (p == null) {
                        // Create new player from balance file
                        p = new Player();
                        p.setUsername(nickname);
                        p.setFullName(nickname);
                        playerMap.put(nickname.toLowerCase(), p);
                    }

                    // Also set club player ID from col H
                    String clubId = getText(row, 7);
                    if (!clubId.isBlank() && (p.getClubPlayerId() == null || p.getClubPlayerId().isBlank())) {
                        p.setClubPlayerId(clubId);
                    }

                    p.setCurrentChips(chips);
                }
            }
        }

        // Step 3: Calculate balance (P&L) and save
        int created = 0, updated = 0;
        for (Player p : playerMap.values()) {
            if (p.getUsername() == null || p.getUsername().isBlank()) continue;
            if (p.getCurrentChips() == null) p.setCurrentChips(BigDecimal.ZERO);
            if (p.getCreditTotal() == null) p.setCreditTotal(BigDecimal.ZERO);

            // P&L = chips - credit (negative = lost, positive = won)
            BigDecimal pnl = p.getCurrentChips().subtract(p.getCreditTotal());
            p.setBalance(pnl);
            p.setActive(true);

            // Check if exists by username first, then by clubPlayerId
            Optional<Player> existing = playerRepository.findByUsername(p.getUsername());
            if (existing.isEmpty() && p.getClubPlayerId() != null && !p.getClubPlayerId().isBlank()) {
                existing = playerRepository.findByClubPlayerId(p.getClubPlayerId());
            }
            if (existing.isPresent()) {
                Player ex = existing.get();
                if (p.getFullName() != null && !p.getFullName().isBlank()) ex.setFullName(p.getFullName());
                if (p.getPhone() != null && !p.getPhone().isBlank()) ex.setPhone(p.getPhone());
                if (p.getClubPlayerId() != null && !p.getClubPlayerId().isBlank()) ex.setClubPlayerId(p.getClubPlayerId());
                ex.setUsername(p.getUsername()); // update username too if matched by clubPlayerId
                ex.setCurrentChips(p.getCurrentChips());
                ex.setCreditTotal(p.getCreditTotal());
                ex.setBalance(p.getBalance());
                playerRepository.save(ex);
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
