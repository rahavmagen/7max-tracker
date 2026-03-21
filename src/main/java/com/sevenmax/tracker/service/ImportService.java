package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
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
    private final PlayerService playerService;
    private final ImportSummaryRepository importSummaryRepository;

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

        // Profit summary metrics (computed from the XLS, stored in DB)
        BigDecimal willExpense = BigDecimal.ZERO;
        BigDecimal generalExpenses = BigDecimal.ZERO;
        BigDecimal bankDeposits = BigDecimal.ZERO;

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

            // Build secondary lookup by full name (for when credit sheet username differs from players sheet)
            Map<String, Player> fullNameMap = new HashMap<>();
            for (Player p : playerMap.values()) {
                if (p.getFullName() != null && !p.getFullName().isBlank()) {
                    fullNameMap.put(p.getFullName().trim().toLowerCase(), p);
                }
            }

            // Build fuzzy lookup (strips spaces/underscores/hyphens) for username matching
            Map<String, Player> fuzzyMap = new HashMap<>();
            for (Map.Entry<String, Player> e : playerMap.entrySet()) {
                fuzzyMap.put(e.getKey().replaceAll("[ _\\-]", ""), e.getValue());
            }

            // Credit sheet (מעקב קרדיטים) - find by name, cols: username(A), name(B), C, D, E, F
            Sheet creditSheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getSheetName().contains("קרדיט")) {
                    creditSheet = wb.getSheetAt(i);
                    break;
                }
            }
            if (creditSheet != null) {
                log.info("Reading credit sheet '{}': {} rows", creditSheet.getSheetName(), creditSheet.getLastRowNum());
                for (int r = 2; r <= creditSheet.getLastRowNum(); r++) {
                    Row row = creditSheet.getRow(r);
                    if (row == null) continue;
                    String username = getText(row, 0);
                    if (username.isBlank()) continue;

                    BigDecimal colC = parseBD(getText(row, 2));
                    BigDecimal colD = parseBD(getText(row, 3));
                    BigDecimal colE = parseBD(getText(row, 4));
                    BigDecimal colF = parseBD(getText(row, 5));
                    BigDecimal total = colC.add(colD).add(colE).add(colF);

                    // 1. Exact username match
                    Player p = playerMap.get(username.toLowerCase());
                    // 2. Fuzzy username match (strips spaces/underscores/hyphens)
                    if (p == null) {
                        p = fuzzyMap.get(username.toLowerCase().replaceAll("[ _\\-]", ""));
                        if (p != null) log.warn("Credit fuzzy username match: '{}' -> '{}'", username, p.getUsername());
                    }
                    // 3. Match by full name (col B) — handles cases where usernames differ between sheets
                    if (p == null) {
                        String fullName = getText(row, 1);
                        if (!fullName.isBlank()) {
                            p = fullNameMap.get(fullName.trim().toLowerCase());
                            if (p != null) log.warn("Credit full-name match: '{}' -> player '{}'", username, p.getUsername());
                        }
                    }
                    if (p != null) p.setCreditTotal(total);
                    else log.warn("Credit row unmatched: '{}'", username);
                }
            } else {
                log.warn("Credit sheet (מעקב קרדיטים) not found in file");
            }

            // הוצאות sheet: col C = general expenses, col H = will/גלגל expenses
            Sheet expSheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getSheetName().contains("הוצאות")) {
                    expSheet = wb.getSheetAt(i);
                    break;
                }
            }
            if (expSheet != null) {
                for (int r = 2; r <= expSheet.getLastRowNum(); r++) {
                    Row row = expSheet.getRow(r);
                    if (row == null) continue;
                    generalExpenses = generalExpenses.add(parseBD(getText(row, 2)));
                    willExpense = willExpense.add(parseBD(getText(row, 7)));
                }
                log.info("Expenses: generalExpenses={} willExpense={}", generalExpenses, willExpense);
            }

            // מעקב הפקדות ומשיכות sheet: cols C+D+E = bank deposits (reuven/yair/tomer)
            Sheet depSheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getSheetName().contains("הפקדות")) {
                    depSheet = wb.getSheetAt(i);
                    break;
                }
            }
            if (depSheet != null) {
                // E1 cell contains SUM formula like "SUM(E3:E69)" — use its end row as the deposit section boundary
                int depositEndRow = 69; // default fallback
                org.apache.poi.ss.usermodel.Row headerRow = depSheet.getRow(0);
                if (headerRow != null) {
                    org.apache.poi.ss.usermodel.Cell e1 = headerRow.getCell(4); // E1
                    if (e1 != null && e1.getCellType() == CellType.FORMULA) {
                        try {
                            String f = e1.getCellFormula(); // e.g. "SUM(E3:E69)"
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile(":(\\w+)\\)").matcher(f);
                            if (m.find()) depositEndRow = Integer.parseInt(m.group(1).replaceAll("[^0-9]", "")) - 1;
                        } catch (Exception ignored) {}
                    }
                }
                log.info("Deposit section rows 2-{}", depositEndRow);
                for (int r = 2; r <= depositEndRow; r++) {
                    Row row = depSheet.getRow(r);
                    if (row == null) continue;
                    // Only sum POSITIVE values (skip withdrawals which are negative)
                    for (int c = 2; c <= 4; c++) {
                        BigDecimal v = parseLeadingNumber(getText(row, c));
                        if (v.compareTo(BigDecimal.ZERO) > 0) bankDeposits = bankDeposits.add(v);
                    }
                }
                log.info("Bank deposits: {}", bankDeposits);
            }
        }

        // Save profit summary as singleton row
        ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
        summary.setId(1L);
        summary.setWillExpense(willExpense);
        summary.setGeneralExpenses(generalExpenses);
        summary.setBankDeposits(bankDeposits);
        summary.setLastUpdated(java.time.LocalDateTime.now());
        importSummaryRepository.save(summary);
        log.info("Saved ImportSummary: will={} expenses={} deposits={}", willExpense, generalExpenses, bankDeposits);

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

            Optional<Player> existing = playerService.findPlayerByUsername(p.getUsername());
            if (existing.isEmpty() && p.getClubPlayerId() != null && !p.getClubPlayerId().isBlank()) {
                existing = playerRepository.findByClubPlayerIdSafe(p.getClubPlayerId()).stream().findFirst();
            }
            if (existing.isPresent()) {
                Player ex = existing.get();
                if (p.getCreditTotal() != null && p.getCreditTotal().compareTo(BigDecimal.ZERO) != 0) {
                    ex.setCreditTotal(p.getCreditTotal());
                    BigDecimal chips = ex.getCurrentChips() != null ? ex.getCurrentChips() : BigDecimal.ZERO;
                    ex.setBalance(chips.subtract(p.getCreditTotal()));
                    playerRepository.save(ex);
                }
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

    /** Extract leading number from a cell that may contain text like "1437 ביט לרועי" */
    private BigDecimal parseLeadingNumber(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;
        val = val.trim();
        // Try direct parse first
        try {
            return new BigDecimal(val.replace(",", ""));
        } catch (Exception ignored) {}
        // Extract leading digits (possibly with decimal point)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^-?[\\d,]+(\\.[\\d]+)?").matcher(val);
        if (m.find()) {
            try {
                return new BigDecimal(m.group().replace(",", ""));
            } catch (Exception ignored) {}
        }
        return BigDecimal.ZERO;
    }
}
