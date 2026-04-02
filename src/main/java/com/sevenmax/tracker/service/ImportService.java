package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.AdminExpenseRepository;
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
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final PlayerRepository playerRepository;
    private final GameResultRepository gameResultRepository;
    private final PlayerService playerService;
    private final ImportSummaryRepository importSummaryRepository;
    private final AdminExpenseRepository expenseRepository;

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
        Map<String, java.math.BigDecimal> adminExpenseTotals = new java.util.LinkedHashMap<>();
        Map<String, Object[]> adminExpenseRows = new java.util.LinkedHashMap<>(); // per-row entries

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
                org.apache.poi.ss.usermodel.FormulaEvaluator creditEval = wb.getCreationHelper().createFormulaEvaluator();
                for (int r = 2; r <= creditSheet.getLastRowNum(); r++) {
                    Row row = creditSheet.getRow(r);
                    if (row == null) continue;
                    String username = getText(row, 0);
                    if (username.isBlank()) continue;

                    BigDecimal colC = parseBD(getTextEvaluated(row, 2, creditEval));
                    BigDecimal colD = parseBD(getTextEvaluated(row, 3, creditEval));
                    BigDecimal colE = parseBD(getTextEvaluated(row, 4, creditEval));
                    BigDecimal colF = parseBD(getTextEvaluated(row, 5, creditEval));
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
                    if (p != null) {
                        p.setCreditTotal(total);
                        if (total.compareTo(BigDecimal.ZERO) != 0)
                            log.info("Credit set: '{}' -> C={} D={} E={} F={} total={}", username, colC, colD, colE, colF, total);
                    } else log.warn("Credit row unmatched: '{}'", username);
                }
            } else {
                log.warn("Credit sheet (מעקב קרדיטים) not found in file");
            }

            // הוצאות sheet: col A = admin username, cols C+E+G = per-admin expenses, col H = wheel expenses
            Sheet expSheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getSheetName().contains("הוצאות")) {
                    expSheet = wb.getSheetAt(i);
                    break;
                }
            }
            if (expSheet != null) {
                org.apache.poi.ss.usermodel.FormulaEvaluator expEval = wb.getCreationHelper().createFormulaEvaluator();
                log.info("הוצאות sheet '{}': {} rows", expSheet.getSheetName(), expSheet.getLastRowNum());

                // Row 2 (index 1) = admin name headers: A=admin1, C=admin2, E=admin3, G=admin4
                // Each admin has their own column; data rows start at row 3 (index 2)
                int[] adminColIndices = {0, 2, 4, 6}; // A, C, E, G
                String[] adminNames = new String[4];
                Row headerRow = expSheet.getRow(1);
                if (headerRow != null) {
                    for (int i = 0; i < adminColIndices.length; i++) {
                        adminNames[i] = getTextEvaluated(headerRow, adminColIndices[i], expEval);
                    }
                }
                log.info("הוצאות admins: A='{}' C='{}' E='{}' G='{}'", adminNames[0], adminNames[1], adminNames[2], adminNames[3]);

                for (int r = 2; r <= expSheet.getLastRowNum(); r++) {
                    Row row = expSheet.getRow(r);
                    if (row == null) continue;
                    willExpense = willExpense.add(parseBD(getTextEvaluated(row, 9, expEval))); // col J = initial wheel
                    for (int i = 0; i < adminColIndices.length; i++) {
                        String adminName = adminNames[i];
                        if (adminName == null || adminName.isBlank()) continue;
                        java.math.BigDecimal amount = parseBD(getTextEvaluated(row, adminColIndices[i], expEval));
                        if (amount.compareTo(java.math.BigDecimal.ZERO) <= 0) continue;
                        String notes = getTextEvaluated(row, adminColIndices[i] + 1, expEval); // next col = description
                        generalExpenses = generalExpenses.add(amount);
                        adminExpenseTotals.merge(adminName, amount, java.math.BigDecimal::add);
                        String entryKey = "XLS:" + adminName + ":" + r + ":" + i;
                        adminExpenseRows.put(entryKey, new Object[]{adminName, amount, notes});
                    }
                }
                log.info("Expenses: generalExpenses={} willExpense={} adminBreakdown={}", generalExpenses, willExpense, adminExpenseTotals);
            }

            // מיקום הכסף sheet: B2 + I2 = bank deposits
            Sheet moneySheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getSheetName().contains("מיקום הכסף")) {
                    moneySheet = wb.getSheetAt(i);
                    break;
                }
            }
            if (moneySheet != null) {
                Row row2 = moneySheet.getRow(1); // row 2 (0-indexed = 1)
                if (row2 != null) {
                    org.apache.poi.ss.usermodel.FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
                    BigDecimal b2 = parseBD(getTextEvaluated(row2, 1, evaluator)); // B2
                    BigDecimal i2 = parseBD(getTextEvaluated(row2, 8, evaluator)); // I2
                    BigDecimal p2 = parseBD(getTextEvaluated(row2, 15, evaluator)); // P2
                    bankDeposits = b2.add(i2).add(p2);
                    log.info("Bank deposits from מיקום הכסף: B2={} I2={} P2={} total={}", b2, i2, p2, bankDeposits);
                }
            } else {
                log.warn("Sheet מיקום הכסף not found");
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

        // Save per-row expense entries — only create new ones, never recreate deleted ones
        int xlsCreated = 0;
        for (Map.Entry<String, Object[]> entry : adminExpenseRows.entrySet()) {
            String uniqueRef = entry.getKey(); // already "XLS:{adminName}:{r}:{i}"
            if (expenseRepository.existsBySourceRef(uniqueRef)) continue; // already exists, skip
            Object[] rowData = entry.getValue();
            com.sevenmax.tracker.entity.AdminExpense exp = new com.sevenmax.tracker.entity.AdminExpense();
            exp.setAdminUsername((String) rowData[0]);
            exp.setAmount((java.math.BigDecimal) rowData[1]);
            String notes = (String) rowData[2];
            exp.setNotes(notes != null && !notes.isBlank() ? notes : "Imported from XLS הוצאות");
            exp.setExpenseDate(java.time.LocalDate.now());
            exp.setCreatedBy("Import");
            exp.setSourceRef(uniqueRef);
            expenseRepository.save(exp);
            xlsCreated++;
        }
        log.info("Admin expense entries from XLS: {} new created (out of {} in XLS)", xlsCreated, adminExpenseRows.size());

        // Save wheel total (col J) as a single AdminExpense record under "Wheel"
        expenseRepository.deleteBySourceRef("XLS:WHEEL");
        if (willExpense.compareTo(java.math.BigDecimal.ZERO) > 0) {
            com.sevenmax.tracker.entity.AdminExpense wheelExp = new com.sevenmax.tracker.entity.AdminExpense();
            wheelExp.setAdminUsername("Wheel");
            wheelExp.setAmount(willExpense);
            wheelExp.setNotes("Wheel expenses from player XLS (הוצאות col J)");
            wheelExp.setExpenseDate(java.time.LocalDate.now());
            wheelExp.setCreatedBy("Import");
            wheelExp.setSourceRef("XLS:WHEEL");
            expenseRepository.save(wheelExp);
            log.info("Saved wheel expense record from XLS col J: amount={}", willExpense);
        }

        log.info("max7 import done: {} players in map", playerMap.size());

        // Step 2: Calculate balance (P&L) and save
        // Note: currentChips will be 0 until a ClubGG report with Club Member Balance is uploaded
        int created = 0, updated = 0;
        List<String> duplicateWarnings = new java.util.ArrayList<>();
        for (Player p : playerMap.values()) {
            if (p.getUsername() == null || p.getUsername().isBlank()) continue;
            if (p.getCurrentChips() == null) p.setCurrentChips(BigDecimal.ZERO);
            if (p.getCreditTotal() == null) p.setCreditTotal(BigDecimal.ZERO);

            BigDecimal pnl = p.getCurrentChips().subtract(p.getCreditTotal());
            p.setBalance(pnl);
            p.setActive(true);

            // clubPlayerId is the authoritative system identifier — always match by it first
            Optional<Player> existing = Optional.empty();
            if (p.getClubPlayerId() != null && !p.getClubPlayerId().isBlank()) {
                existing = playerRepository.findByClubPlayerIdSafe(p.getClubPlayerId()).stream().findFirst();
            }
            if (existing.isEmpty()) {
                existing = playerService.findPlayerByUsername(p.getUsername());
                // Warn if username matched but with different casing (potential manual duplicate)
                if (existing.isPresent() && !existing.get().getUsername().equals(p.getUsername())) {
                    String msg = "Username case mismatch: XLS='" + p.getUsername() + "' matched DB='" + existing.get().getUsername() + "' — merged automatically";
                    duplicateWarnings.add(msg);
                    log.warn("Import duplicate warning: {}", msg);
                }
            }
            if (existing.isPresent()) {
                Player ex = existing.get();
                // Always sync name, phone, clubPlayerId from XLS (authoritative source)
                if (p.getFullName() != null && !p.getFullName().isBlank()) ex.setFullName(p.getFullName());
                if (p.getPhone() != null && !p.getPhone().isBlank()) ex.setPhone(p.getPhone());
                if (p.getClubPlayerId() != null && !p.getClubPlayerId().isBlank()) ex.setClubPlayerId(p.getClubPlayerId());
                BigDecimal newCredit = p.getCreditTotal() != null ? p.getCreditTotal() : BigDecimal.ZERO;
                ex.setCreditTotal(newCredit);
                BigDecimal chips = ex.getCurrentChips() != null ? ex.getCurrentChips() : BigDecimal.ZERO;
                ex.setBalance(chips.subtract(newCredit));
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
        if (!duplicateWarnings.isEmpty()) result.put("duplicateWarnings", duplicateWarnings);
        return result;
    }

    /** Compare XLS players sheet against DB — returns who is in XLS but not in DB */
    public Map<String, Object> compareWithXls(MultipartFile max7File) throws Exception {
        List<Map<String, String>> inXls = new java.util.ArrayList<>();
        List<Map<String, String>> missingFromDb = new java.util.ArrayList<>();
        List<Map<String, String>> xlsDuplicates = new java.util.ArrayList<>();

        Set<String> seenUsernames = new java.util.LinkedHashSet<>();
        Set<String> seenClubIds = new java.util.LinkedHashSet<>();

        try (InputStream is = max7File.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet1 = wb.getSheetAt(0);
            for (int r = 1; r <= sheet1.getLastRowNum(); r++) {
                Row row = sheet1.getRow(r);
                if (row == null) continue;
                String username = getText(row, 0);
                if (username.isBlank()) continue;

                String fullName = getText(row, 1);
                String phone = getText(row, 2);
                String rawId = getText(row, 3);
                if (!rawId.isBlank()) {
                    rawId = rawId.replace(".0", "").trim();
                    if (rawId.length() == 8) rawId = rawId.substring(0, 4) + "-" + rawId.substring(4);
                }

                Map<String, String> entry = new java.util.LinkedHashMap<>();
                entry.put("row", String.valueOf(r + 1));
                entry.put("username", username);
                entry.put("fullName", fullName);
                entry.put("clubPlayerId", rawId);

                // Check for duplicates within XLS
                boolean isDup = false;
                if (!rawId.isBlank() && seenClubIds.contains(rawId.toLowerCase())) {
                    entry.put("dupReason", "duplicate clubPlayerId in XLS: " + rawId);
                    xlsDuplicates.add(entry);
                    isDup = true;
                } else if (seenUsernames.contains(username.toLowerCase())) {
                    entry.put("dupReason", "duplicate username in XLS: " + username);
                    xlsDuplicates.add(entry);
                    isDup = true;
                }
                if (!rawId.isBlank()) seenClubIds.add(rawId.toLowerCase());
                seenUsernames.add(username.toLowerCase());

                if (!isDup) inXls.add(entry);
            }
        }

        // Check each XLS player against DB
        for (Map<String, String> xlsPlayer : inXls) {
            String clubId = xlsPlayer.get("clubPlayerId");
            String username = xlsPlayer.get("username");

            boolean foundInDb = false;
            if (!clubId.isBlank()) {
                foundInDb = !playerRepository.findByClubPlayerIdSafe(clubId).isEmpty();
            }
            if (!foundInDb) {
                foundInDb = playerService.findPlayerByUsername(username).isPresent();
            }
            if (!foundInDb) {
                missingFromDb.add(xlsPlayer);
            }
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("xlsCount", inXls.size() + xlsDuplicates.size());
        result.put("xlsUniqueCount", inXls.size());
        result.put("dbCount", playerRepository.count());
        result.put("missingFromDb", missingFromDb);
        result.put("xlsDuplicates", xlsDuplicates);
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

    private String getTextEvaluated(Row row, int col, org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        org.apache.poi.ss.usermodel.CellValue cv = evaluator.evaluate(cell);
        if (cv == null) return "";
        return switch (cv.getCellType()) {
            case NUMERIC -> {
                double v = cv.getNumberValue();
                if (v == Math.floor(v)) yield String.valueOf((long) v);
                yield String.valueOf(v);
            }
            case STRING -> cv.getStringValue().trim();
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

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> importExpensesOnly(org.springframework.web.multipart.MultipartFile file) throws Exception {
        int imported = 0;
        int skipped = 0;

        try (org.apache.poi.ss.usermodel.Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(file.getInputStream())) {
            // Find הוצאות sheet
            org.apache.poi.ss.usermodel.Sheet expSheet = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getSheetName().contains("הוצאות")) {
                    expSheet = wb.getSheetAt(i);
                    break;
                }
            }
            if (expSheet == null) {
                throw new IllegalArgumentException("הוצאות sheet not found in this file");
            }

            org.apache.poi.ss.usermodel.FormulaEvaluator expEval = wb.getCreationHelper().createFormulaEvaluator();

            // Row 2 (index 1) = admin name headers: A, C, E, G
            int[] adminColIndices = {0, 2, 4, 6};
            String[] adminNames = new String[4];
            org.apache.poi.ss.usermodel.Row headerRow = expSheet.getRow(1);
            if (headerRow != null) {
                for (int i = 0; i < adminColIndices.length; i++) {
                    adminNames[i] = getTextEvaluated(headerRow, adminColIndices[i], expEval);
                }
            }

            // Collect per-row expense entries
            java.util.Map<String, Object[]> adminExpenseRows = new java.util.LinkedHashMap<>();
            java.math.BigDecimal willExpense = java.math.BigDecimal.ZERO;

            for (int r = 2; r <= expSheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = expSheet.getRow(r);
                if (row == null) continue;
                willExpense = willExpense.add(parseBD(getTextEvaluated(row, 9, expEval))); // col J = wheel
                for (int i = 0; i < adminColIndices.length; i++) {
                    String adminName = adminNames[i];
                    if (adminName == null || adminName.isBlank()) continue;
                    java.math.BigDecimal amount = parseBD(getTextEvaluated(row, adminColIndices[i], expEval));
                    if (amount.compareTo(java.math.BigDecimal.ZERO) <= 0) continue;
                    String notes = getTextEvaluated(row, adminColIndices[i] + 1, expEval);
                    String entryKey = "XLS:" + adminName + ":" + r + ":" + i;
                    adminExpenseRows.put(entryKey, new Object[]{adminName, amount, notes});
                }
            }

            // Save per-row entries (skip duplicates)
            for (Map.Entry<String, Object[]> entry : adminExpenseRows.entrySet()) {
                String uniqueRef = entry.getKey();
                if (expenseRepository.existsBySourceRef(uniqueRef)) {
                    skipped++;
                    continue;
                }
                Object[] rowData = entry.getValue();
                com.sevenmax.tracker.entity.AdminExpense exp = new com.sevenmax.tracker.entity.AdminExpense();
                exp.setAdminUsername((String) rowData[0]);
                exp.setAmount((java.math.BigDecimal) rowData[1]);
                String notes = (String) rowData[2];
                exp.setNotes(notes != null && !notes.isBlank() ? notes : "Imported from XLS הוצאות");
                exp.setExpenseDate(java.time.LocalDate.now());
                exp.setCreatedBy("Import");
                exp.setSourceRef(uniqueRef);
                expenseRepository.save(exp);
                imported++;
            }

            // Wheel total (col J) — replace existing XLS:WHEEL record
            expenseRepository.deleteBySourceRef("XLS:WHEEL");
            if (willExpense.compareTo(java.math.BigDecimal.ZERO) > 0) {
                com.sevenmax.tracker.entity.AdminExpense wheelExp = new com.sevenmax.tracker.entity.AdminExpense();
                wheelExp.setAdminUsername("Wheel");
                wheelExp.setAmount(willExpense);
                wheelExp.setNotes("Wheel expenses from player XLS (הוצאות col J)");
                wheelExp.setExpenseDate(java.time.LocalDate.now());
                wheelExp.setCreatedBy("Import");
                wheelExp.setSourceRef("XLS:WHEEL");
                expenseRepository.save(wheelExp);
                imported++;
            }

            // מיקום הכסף sheet — update bank deposits in ImportSummary
            java.math.BigDecimal bankDeposits = java.math.BigDecimal.ZERO;
            org.apache.poi.ss.usermodel.FormulaEvaluator moneyEval = wb.getCreationHelper().createFormulaEvaluator();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getSheetName().contains("מיקום הכסף")) {
                    org.apache.poi.ss.usermodel.Sheet moneySheet = wb.getSheetAt(i);
                    org.apache.poi.ss.usermodel.Row row2 = moneySheet.getRow(1);
                    if (row2 != null) {
                        java.math.BigDecimal b2 = parseBD(getTextEvaluated(row2, 1, moneyEval));
                        java.math.BigDecimal i2 = parseBD(getTextEvaluated(row2, 8, moneyEval));
                        java.math.BigDecimal p2 = parseBD(getTextEvaluated(row2, 15, moneyEval));
                        bankDeposits = b2.add(i2).add(p2);
                        log.info("importExpensesOnly: bankDeposits B2={} I2={} P2={} total={}", b2, i2, p2, bankDeposits);
                    }
                    break;
                }
            }
            if (bankDeposits.compareTo(java.math.BigDecimal.ZERO) > 0) {
                com.sevenmax.tracker.entity.ImportSummary summary = importSummaryRepository.findById(1L).orElse(new com.sevenmax.tracker.entity.ImportSummary());
                summary.setId(1L);
                summary.setBankDeposits(bankDeposits);
                summary.setLastUpdated(java.time.LocalDateTime.now());
                importSummaryRepository.save(summary);
                log.info("importExpensesOnly: updated ImportSummary bankDeposits={}", bankDeposits);
            }

            log.info("importExpensesOnly: imported={} skipped={} wheel={} bankDeposits={}", imported, skipped, willExpense, bankDeposits);
        }

        return Map.of("imported", imported, "skipped", skipped);
    }
}
