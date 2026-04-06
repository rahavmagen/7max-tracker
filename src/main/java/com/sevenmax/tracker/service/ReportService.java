package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.entity.AdminExpense;
import com.sevenmax.tracker.repository.*;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
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
    private final ImportSummaryRepository importSummaryRepository;
    private final AdminExpenseRepository adminExpenseRepository;
    private final XlsMatchingService xlsMatchingService;

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

            // Store file bytes in DB (works on cloud with ephemeral filesystem)
            report.setFileData(fileBytes);
            // Also save to disk for local dev / backfill legacy
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
                    if ((player.getClubPlayerId() == null || player.getClubPlayerId().isBlank()) && balanceClubId != null) {
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

            // Always save chip total from this XLS (used to restore ImportSummary on delete)
            BigDecimal newXlsTotal = newChipsMap.values().stream()
                .map(v -> (BigDecimal) v[0])
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            report.setChipsTotal(newXlsTotal);

            // Compute chip mismatch warning (latest report only)
            if (isLatestReport) {
                ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
                java.time.LocalDate prevReportDate = summary.getLastReportDate();
                BigDecimal prevChipsTotal = summary.getLastReportChipsTotal() != null ? summary.getLastReportChipsTotal() : BigDecimal.ZERO;

                if (prevReportDate != null) {
                    java.time.LocalDateTime cutoff = prevReportDate.atStartOfDay();
                    BigDecimal deposits = transactionRepository.sumDepositsSince(cutoff);
                    BigDecimal credits = transactionRepository.sumCreditsSince(cutoff);
                    BigDecimal wheel = transactionRepository.sumWheelExpensesSince(cutoff);
                    BigDecimal rake = gameResultRepository.sumRakeSince(cutoff);
                    BigDecimal expectedTotal = prevChipsTotal
                        .add(deposits != null ? deposits : BigDecimal.ZERO)
                        .add(credits != null ? credits : BigDecimal.ZERO)
                        .subtract(wheel != null ? wheel : BigDecimal.ZERO)
                        .add(rake != null ? rake : BigDecimal.ZERO);
                    BigDecimal mismatch = newXlsTotal.subtract(expectedTotal).abs();
                    if (mismatch.compareTo(new BigDecimal("1")) > 0) {
                        report.setChipMismatch(mismatch);
                        report.setChipMismatchExpected(expectedTotal);
                        report.setChipMismatchActual(newXlsTotal);
                        log.warn("Chip mismatch on upload: expected={} actual={} diff={}", expectedTotal, newXlsTotal, mismatch);
                    }
                }

                // Read bank deposits from Club Overview P2
                if (overviewSheet != null) {
                    Row overviewRow1 = overviewSheet.getRow(1); // row 2 (0-indexed)
                    if (overviewRow1 != null) {
                        BigDecimal p2 = parseBigDecimal(getCellValue(overviewRow1, 15)); // col P
                        if (p2 != null && p2.compareTo(BigDecimal.ZERO) > 0) {
                            summary.setBankDeposits(p2);
                        }
                    }
                }

                // Save new chip snapshot for next check
                summary.setId(1L);
                summary.setLastReportChipsTotal(newXlsTotal);
                summary.setLastReportDate(report.getPeriodEnd());
                summary.setLastUpdated(LocalDateTime.now());
                importSummaryRepository.save(summary);
            }

            // Parse מעקב קרדיטים → update player creditTotal if sheet exists
            parseCreditSheet(workbook);

            // Parse Trade Record → create CREDIT/PAYMENT transactions (skip already-imported)
            parseTradeRecord(workbook, report);

            // Backfill AdminExpense for any WHEEL_EXPENSE transaction that doesn't have one yet
            backfillWheelExpenseAdminRecords();

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

    private void parseTradeRecord(Workbook workbook, Report report) {
        Sheet sheet = workbook.getSheet("Trade Record");
        if (sheet == null) return;

        Long reportId = report.getId();
        List<String> wheelWarnings = new java.util.ArrayList<>();
        // Tracks "from:playerId:amount" and "to:playerId:amount" for transfers confirmed this upload
        // so the other XLS side of a confirmed transfer is not treated as unmatched.
        Set<String> confirmedTransferSides = new java.util.HashSet<>();
        // Accumulates net chip delta per player for group-based pending confirmation
        Map<Long, BigDecimal> xlsNetByPlayer = new java.util.LinkedHashMap<>();

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

            BigDecimal rawAmount = parseBigDecimal(amountStr);
            BigDecimal amount = rawAmount.abs();
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

            // Wheel expense: "Send Chips" negative in column G, AND amount matches the player's
            // cost in the nightly MTT (9 PM main event, starts between 20:20 and 21:40 on txDate
            // or the previous day). If no match found → show warning and treat as CREDIT.
            boolean isWheelExpense = false;
            boolean pendingWheelWarning = false;
            BigDecimal pendingWheelMttCost = null;
            if (tradeType.equals("Send Chips") && rawAmount.compareTo(BigDecimal.ZERO) < 0) {
                log.info("[WHEEL-DEBUG] Send Chips negative: player={} playerId={} amount={} date={} sourceRef={}",
                        player.getUsername(), player.getId(), amount, txDate, sourceRef);
                BigDecimal mttCost = getNightlyMttCost(txDate, player.getId());
                log.info("[WHEEL-DEBUG] getNightlyMttCost(date={}, playerId={}) => {}", txDate, player.getId(), mttCost);
                if (mttCost == null || amount.compareTo(mttCost) != 0) {
                    // Also check previous day (wheel expense may be recorded the morning after,
                    // or the amount on the current day doesn't match)
                    BigDecimal prevCost = getNightlyMttCost(txDate.minusDays(1), player.getId());
                    log.info("[WHEEL-DEBUG] getNightlyMttCost(prevDay={}, playerId={}) => {}", txDate.minusDays(1), player.getId(), prevCost);
                    if (prevCost != null && amount.compareTo(prevCost) == 0) {
                        mttCost = prevCost;
                    }
                }
                if (mttCost != null && amount.compareTo(mttCost) == 0) {
                    isWheelExpense = true;
                    log.info("Wheel expense detected: player={} amount={} matches MTT cost on {}", player.getUsername(), amount, txDate);
                } else {
                    log.warn("[WHEEL-DEBUG] No wheel match: player={} amount={} mttCost={} — treating as non-wheel Send Chips",
                            player.getUsername(), amount, mttCost);
                    // Defer warning — only emit if not handled by pending transfer/transaction checks below
                    pendingWheelWarning = true;
                    pendingWheelMttCost = mttCost;
                }
            }

            // Check for matching pending PlayerTransfer — runs even if already imported,
            // so re-uploading an XLS after creating the transfer on the website still works.
            // Wheel expenses are not matched to pending transfers.
            boolean transferConfirmed = false;
            if (!isWheelExpense) {
                if (tradeType.equals("Send Chips")) {
                    // Exact match
                    var matchedTransfer = playerTransferRepository.findFirstByFromPlayerIdAndAmountAndConfirmedFalse(player.getId(), amount);
                    if (matchedTransfer.isPresent()) {
                        PlayerTransfer transfer = matchedTransfer.get();
                        transfer.setConfirmed(true);
                        playerTransferRepository.save(transfer);
                        // Record both sides so the opposite XLS row is not treated as unmatched
                        confirmedTransferSides.add("from:" + player.getId() + ":" + amount);
                        if (transfer.getToPlayer() != null)
                            confirmedTransferSides.add("to:" + transfer.getToPlayer().getId() + ":" + amount);
                        log.info("XLS matched pending transfer id={} (Send Chips, player={}, amount={})", transfer.getId(), player.getUsername(), amount);
                        transferConfirmed = true;
                    } else {
                        // Sum match: find multiple pending transfers from this player that sum to amount
                        List<com.sevenmax.tracker.entity.PlayerTransfer> candidates = playerTransferRepository.findByFromPlayerIdAndConfirmedFalse(player.getId());
                        transferConfirmed = confirmSumMatch(candidates, amount, player.getUsername(), "Send Chips");
                    }
                    // Bug D: transfer was confirmed on website before this XLS upload — full amount match
                    if (!transferConfirmed) {
                        var preConfirmed = playerTransferRepository.findFirstByFromPlayerIdAndAmountAndConfirmedTrue(player.getId(), amount);
                        if (preConfirmed.isPresent()) {
                            transferConfirmed = true;
                            log.info("Send Chips already handled by pre-confirmed transfer id={} (player={}, amount={})", preConfirmed.get().getId(), player.getUsername(), amount);
                        }
                    }
                    // Bug B: XLS amount = confirmed transfer partial amount + pending credit (sum match)
                    if (!transferConfirmed) {
                        List<com.sevenmax.tracker.entity.PlayerTransfer> confirmedTransfers = playerTransferRepository.findByFromPlayerIdAndConfirmedTrue(player.getId());
                        for (com.sevenmax.tracker.entity.PlayerTransfer ct : confirmedTransfers) {
                            BigDecimal remainder = amount.subtract(ct.getAmount());
                            if (remainder.compareTo(BigDecimal.ZERO) > 0) {
                                var pendingPart = transactionRepository.findFirstByPlayerIdAndAmountAndPendingConfirmationTrue(player.getId(), remainder);
                                if (pendingPart.isPresent() && pendingPart.get().getSourceRef() != null
                                        && !pendingPart.get().getSourceRef().startsWith("TRADE:")) {
                                    pendingPart.get().setPendingConfirmation(false);
                                    transactionRepository.save(pendingPart.get());
                                    transferConfirmed = true;
                                    log.info("Send Chips sum-match: confirmed transfer id={} amount={} + pending tx id={} amount={} = {} (player={})",
                                            ct.getId(), ct.getAmount(), pendingPart.get().getId(), remainder, amount, player.getUsername());
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    // Exact match
                    var matchedTransfer = playerTransferRepository.findFirstByToPlayerIdAndAmountAndConfirmedFalse(player.getId(), amount);
                    if (matchedTransfer.isPresent()) {
                        PlayerTransfer transfer = matchedTransfer.get();
                        transfer.setConfirmed(true);
                        playerTransferRepository.save(transfer);
                        // Record both sides so the opposite XLS row is not treated as unmatched
                        confirmedTransferSides.add("to:" + player.getId() + ":" + amount);
                        if (transfer.getFromPlayer() != null)
                            confirmedTransferSides.add("from:" + transfer.getFromPlayer().getId() + ":" + amount);
                        log.info("XLS matched pending transfer id={} (Claim Chips, player={}, amount={})", transfer.getId(), player.getUsername(), amount);
                        transferConfirmed = true;
                    } else {
                        // Sum match: find multiple pending transfers to this player that sum to amount
                        List<com.sevenmax.tracker.entity.PlayerTransfer> candidates = playerTransferRepository.findByToPlayerIdAndConfirmedFalse(player.getId());
                        transferConfirmed = confirmSumMatch(candidates, amount, player.getUsername(), "Claim Chips");
                    }
                    // Bug D: transfer was confirmed on website before this XLS upload — full amount match
                    if (!transferConfirmed) {
                        var preConfirmed = playerTransferRepository.findFirstByToPlayerIdAndAmountAndConfirmedTrue(player.getId(), amount);
                        if (preConfirmed.isPresent()) {
                            transferConfirmed = true;
                            log.info("Claim Chips already handled by pre-confirmed transfer id={} (player={}, amount={})", preConfirmed.get().getId(), player.getUsername(), amount);
                        }
                    }
                }
            }

            // Accumulate xls_net per player for group-based pending confirmation (runs even on re-upload)
            if (!isWheelExpense && !transferConfirmed) {
                BigDecimal contribution = tradeType.equals("Send Chips") ? amount : amount.negate();
                xlsNetByPlayer.merge(player.getId(), contribution, BigDecimal::add);
            }

            // Now emit deferred wheel warning — only if this Send Chips negative was not handled
            // by a pending transfer or pending transaction confirmation
            if (pendingWheelWarning && !transferConfirmed && !alreadyImported) {
                String warning = player.getUsername() + " (" + amount + " on " + txDate + ")";
                wheelWarnings.add(warning);
                log.warn("Send Chips negative but no MTT cost match: player={} amount={} mttCost={} date={} — treating as CREDIT",
                        player.getUsername(), amount, pendingWheelMttCost, txDate);
            }

            // AdminExpense for wheel — must run even if transaction was already imported
            if (isWheelExpense) {
                String wheelExpenseRef = "WHEEL:" + sourceRef;
                if (!adminExpenseRepository.existsBySourceRef(wheelExpenseRef)) {
                    AdminExpense exp = new AdminExpense();
                    exp.setAdminUsername("Wheel");
                    exp.setAmount(amount);
                    exp.setNotes("Wheel - " + player.getUsername());
                    exp.setExpenseDate(txDate);
                    exp.setCreatedBy("Import");
                    exp.setSourceRef(wheelExpenseRef);
                    adminExpenseRepository.save(exp);
                    log.info("Created AdminExpense for wheel: player={} amount={} date={}", player.getUsername(), amount, txDate);
                }
            }

            // If transfer was confirmed on re-upload, delete the orphan "Trade Record" transaction
            if (alreadyImported && transferConfirmed) {
                transactionRepository.findBySourceRef(sourceRef).forEach(orphan -> {
                    transactionRepository.delete(orphan);
                    log.info("Deleted orphan Trade Record transaction id={} for sourceRef={} (late-matched transfer)", orphan.getId(), sourceRef);
                });
            }

            if (alreadyImported || transferConfirmed) continue;

            // Check if this XLS row is the "other side" of a transfer confirmed in this same upload
            if (!isWheelExpense) {
                String sideKey = tradeType.equals("Send Chips")
                        ? "from:" + player.getId() + ":" + amount
                        : "to:" + player.getId() + ":" + amount;
                if (confirmedTransferSides.remove(sideKey)) { // remove so only ONE extra row is absorbed
                    log.info("Skipping XLS row — other side of transfer confirmed this upload: player={} amount={} tradeType={}", player.getUsername(), amount, tradeType);
                    continue;
                }
            }

            // Check for a cancelling XLS_UNMATCHED pair (Got Chips + Reduce Chips, same player, same amount)
            if (!isWheelExpense) {
                Transaction.Type newType = tradeType.equals("Send Chips") ? Transaction.Type.CREDIT : Transaction.Type.PAYMENT;
                Transaction.Type oppositeType = tradeType.equals("Send Chips") ? Transaction.Type.PAYMENT : Transaction.Type.CREDIT;
                var cancelMatch = transactionRepository.findFirstByPlayerIdAndAmountAndTypeAndPendingConfirmationTrue(player.getId(), amount, oppositeType);
                if (cancelMatch.isPresent() && cancelMatch.get().getSourceRef() != null && cancelMatch.get().getSourceRef().startsWith("TRADE:")) {
                    transactionRepository.delete(cancelMatch.get());
                    log.info("Auto-cancelled XLS_UNMATCHED pair for player={} amount={} ({} ↔ {})", player.getUsername(), amount, newType, oppositeType);
                    continue;
                }
            }

            Transaction tx = new Transaction();
            tx.setPlayer(player);
            if (isWheelExpense) {
                tx.setType(Transaction.Type.WHEEL_EXPENSE);
                tx.setNotes("Trade Record: Wheel Expense (Send Chips negative)");
                log.info("Trade Record wheel expense: player={} amount={} date={}", player.getUsername(), amount, txDate);
            } else {
                tx.setType(tradeType.equals("Send Chips") ? Transaction.Type.CREDIT : Transaction.Type.PAYMENT);
                // "Send Chips" = player got chips; "Claim Chips" = club took chips back
                tx.setNotes(tradeType.equals("Send Chips") ? "Got Chips" : "Reduce Chips");
                tx.setPendingConfirmation(true); // unmatched — admin must review
            }
            tx.setAmount(amount);
            tx.setTransactionDate(txDate);
            tx.setSourceRef(sourceRef);
            tx.setCreatedByUsername("Import");
            tx.setReportId(reportId);
            transactionRepository.save(tx);
        }

        // Group-based pending confirmation: per player, compare xls_net to expected pending chip delta.
        // Confirms all pending screen-entered transactions if the net matches the XLS Trade Record.
        for (Map.Entry<Long, BigDecimal> entry : xlsNetByPlayer.entrySet()) {
            Long playerId = entry.getKey();
            BigDecimal xlsNet = entry.getValue();
            List<Transaction> pending = transactionRepository.findByPlayerIdAndPendingConfirmationTrue(playerId)
                    .stream()
                    .filter(tx -> tx.getSourceRef() == null || !tx.getSourceRef().startsWith("TRADE:"))
                    .collect(java.util.stream.Collectors.toList());
            if (xlsMatchingService.isGroupMatch(pending, xlsNet)) {
                pending.forEach(tx -> {
                    tx.setPendingConfirmation(false);
                    transactionRepository.save(tx);
                    log.info("Group match: confirmed pending tx id={} type={} amount={} player={}",
                            tx.getId(), tx.getType(), tx.getAmount(), tx.getPlayer().getUsername());
                });
                // Delete any XLS_UNMATCHED entries created for this player in this upload
                if (reportId != null) {
                    transactionRepository.findByReportId(reportId).stream()
                            .filter(tx -> tx.getPlayer().getId().equals(playerId)
                                    && Boolean.TRUE.equals(tx.getPendingConfirmation()))
                            .forEach(transactionRepository::delete);
                }
            }

            // Also try to confirm unconfirmed PlayerTransfers by group match.
            // Payers appear as "Claim Chips" in the XLS → xlsNet is negative for net payers.
            // If abs(xlsNet) equals the sum of all pending FROM-transfers for this player, confirm them all.
            List<com.sevenmax.tracker.entity.PlayerTransfer> pendingFromTransfers =
                    playerTransferRepository.findByFromPlayerIdAndConfirmedFalse(playerId);
            if (!pendingFromTransfers.isEmpty()) {
                BigDecimal fromSum = pendingFromTransfers.stream()
                        .map(com.sevenmax.tracker.entity.PlayerTransfer::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (fromSum.compareTo(xlsNet.abs()) == 0) {
                    pendingFromTransfers.forEach(pt -> {
                        pt.setConfirmed(true);
                        playerTransferRepository.save(pt);
                        log.info("Group match: confirmed pending PlayerTransfer id={} from={} to={} amount={}",
                                pt.getId(),
                                pt.getFromPlayer() != null ? pt.getFromPlayer().getUsername() : "?",
                                pt.getToPlayer() != null ? pt.getToPlayer().getUsername() : "?",
                                pt.getAmount());
                    });
                }
            }
        }

        if (!wheelWarnings.isEmpty()) {
            report.setWheelExpenseWarnings(wheelWarnings);
            log.warn("Wheel expense warnings (no MTT match): {}", wheelWarnings);
        }
    }

    // Returns the player's buyIn cost in the nightly MTT on the given date,
    // Confirm a set of pending transfers whose amounts sum exactly to targetAmount.
    // Returns true if a matching combination was found and confirmed.
    private boolean confirmSumMatch(List<com.sevenmax.tracker.entity.PlayerTransfer> candidates, BigDecimal targetAmount, String playerUsername, String tradeType) {
        // Try all subsets (up to reasonable size) that sum to targetAmount
        int n = Math.min(candidates.size(), 10); // cap at 10 to avoid combinatorial explosion
        for (int size = 2; size <= n; size++) {
            if (findAndConfirmSubset(candidates, size, 0, BigDecimal.ZERO, targetAmount, new java.util.ArrayList<>(), playerUsername, tradeType)) {
                return true;
            }
        }
        return false;
    }

    private boolean findAndConfirmSubset(List<com.sevenmax.tracker.entity.PlayerTransfer> candidates, int size, int start,
                                          BigDecimal current, BigDecimal target, java.util.List<com.sevenmax.tracker.entity.PlayerTransfer> chosen,
                                          String playerUsername, String tradeType) {
        if (chosen.size() == size) {
            if (current.compareTo(target) == 0) {
                chosen.forEach(t -> {
                    t.setConfirmed(true);
                    playerTransferRepository.save(t);
                    log.info("XLS sum-matched pending transfer id={} ({}, player={}, amount={})", t.getId(), tradeType, playerUsername, t.getAmount());
                });
                return true;
            }
            return false;
        }
        for (int i = start; i < candidates.size(); i++) {
            chosen.add(candidates.get(i));
            if (findAndConfirmSubset(candidates, size, i + 1, current.add(candidates.get(i).getAmount()), target, chosen, playerUsername, tradeType)) {
                return true;
            }
            chosen.remove(chosen.size() - 1);
        }
        return false;
    }

    // or null if no such session / player not found in it.
    // buyIn already includes rake, so we don't add rakePaid.
    private BigDecimal getNightlyMttCost(LocalDate date, Long playerId) {
        // Window covers nightly MTT and Shabbat games (can start as early as 20:00)
        LocalDateTime windowStart = date.atTime(19, 30);
        LocalDateTime windowEnd = date.atTime(23, 59);
        List<GameSession> sessions = gameSessionRepository.findByGameTypeAndStartTimeBetween(
                GameSession.GameType.MTT, windowStart, windowEnd);
        log.info("[WHEEL-DEBUG] getNightlyMttCost: window={} to {}, sessions found={}", windowStart, windowEnd, sessions.size());
        if (sessions.isEmpty()) {
            // Log all MTT sessions near this date for diagnostics
            LocalDateTime broadStart = date.minusDays(1).atTime(0, 0);
            LocalDateTime broadEnd = date.plusDays(1).atTime(23, 59);
            List<GameSession> nearby = gameSessionRepository.findByGameTypeAndStartTimeBetween(GameSession.GameType.MTT, broadStart, broadEnd);
            log.info("[WHEEL-DEBUG] No MTT in window. Nearby MTT sessions (±1 day): {}", nearby.stream().map(s -> s.getId() + "@" + s.getStartTime()).toList());
            return null;
        }
        // Check all sessions in the window — return the player's buy-in from whichever they participated in
        for (GameSession session : sessions) {
            List<GameResult> results = gameResultRepository.findBySessionId(session.getId());
            log.info("[WHEEL-DEBUG] Session id={} startTime={}, results count={}", session.getId(), session.getStartTime(), results.size());
            Optional<BigDecimal> cost = results.stream()
                    .filter(r -> r.getPlayer() != null && r.getPlayer().getId().equals(playerId))
                    .map(r -> {
                        log.info("[WHEEL-DEBUG] Player {} buyIn={}", r.getPlayer().getUsername(), r.getBuyIn());
                        return r.getBuyIn() != null ? r.getBuyIn() : BigDecimal.ZERO;
                    })
                    .findFirst();
            if (cost.isPresent()) return cost.get();
        }
        return null;
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
            BigDecimal buyIn = parseBigDecimal(getCellValue(row, 6));  // col G = buy-in
            BigDecimal fee   = parseBigDecimal(getCellValue(row, 7));  // col H = fee
            final BigDecimal entryFee = buyIn.add(fee);

            mttSessions.stream()
                .filter(s -> s.getStartTime() != null && s.getStartTime().withSecond(0).withNano(0).equals(st.withSecond(0).withNano(0)))
                .findFirst()
                .ifPresent(s -> {
                    if (et != null) s.setEndTime(et);
                    if (entryFee.compareTo(BigDecimal.ZERO) > 0) s.setEntryFee(entryFee);
                    gameSessionRepository.save(s);
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

    private void backfillWheelExpenseAdminRecords() {
        List<Transaction> wheelTxs = transactionRepository.findAllWheelExpenses();
        for (Transaction tx : wheelTxs) {
            String expRef = tx.getSourceRef() != null ? "WHEEL:" + tx.getSourceRef() : null;
            // Skip if already has a matching AdminExpense
            if (expRef != null && adminExpenseRepository.existsBySourceRef(expRef)) continue;
            // Also skip if a null-sourceRef entry already exists for this player+amount+date
            // (manual wheel expenses have no sourceRef)
            if (expRef == null) continue;

            AdminExpense exp = new AdminExpense();
            exp.setAdminUsername("Wheel");
            exp.setAmount(tx.getAmount());
            String playerName = tx.getPlayer() != null ? tx.getPlayer().getUsername() : "?";
            exp.setNotes("Wheel - " + playerName);
            exp.setExpenseDate(tx.getTransactionDate());
            exp.setCreatedBy("Import");
            exp.setSourceRef(expRef);
            adminExpenseRepository.save(exp);
            log.info("Backfilled AdminExpense for wheel tx id={} player={} amount={}", tx.getId(), playerName, tx.getAmount());
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

        // Delete trade transactions imported from this XLS and reverse their balance effects
        List<Transaction> tradeTxs = transactionRepository.findByReportId(reportId);
        for (Transaction tx : tradeTxs) {
            Player player = tx.getPlayer();
            boolean added = tx.getType() == Transaction.Type.DEPOSIT || tx.getType() == Transaction.Type.PAYMENT;
            player.setBalance(player.getBalance().add(added ? tx.getAmount().negate() : tx.getAmount()));
            playerRepository.save(player);
        }
        transactionRepository.deleteAll(tradeTxs);

        reportRepository.deleteById(reportId);

        // Update ImportSummary to reflect the most recent remaining report
        List<Report> remaining = reportRepository.findAll().stream()
            .filter(r -> r.getPeriodEnd() != null)
            .sorted(java.util.Comparator.comparing(Report::getPeriodEnd).reversed())
            .toList();

        ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
        summary.setId(1L);
        if (remaining.isEmpty()) {
            summary.setLastReportChipsTotal(BigDecimal.ZERO);
            summary.setLastReportDate(null);
            log.info("All reports deleted — ImportSummary cleared");
        } else {
            Report latest = remaining.get(0);
            summary.setLastReportDate(latest.getPeriodEnd());
            // Use stored chipsTotal if available (set on upload after this feature was added),
            // otherwise fall back to current actual chip sum as best approximation
            if (latest.getChipsTotal() != null) {
                summary.setLastReportChipsTotal(latest.getChipsTotal());
            } else {
                BigDecimal actualChips = playerRepository.findAll().stream()
                    .map(p -> p.getCurrentChips() != null ? p.getCurrentChips() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                summary.setLastReportChipsTotal(actualChips);
            }
            log.info("ImportSummary rolled back to report periodEnd={} chipsTotal={}", latest.getPeriodEnd(), summary.getLastReportChipsTotal());
        }
        importSummaryRepository.save(summary);

        log.info("Deleted report {} along with {} sessions", reportId, sessions.size());
    }

    public List<Report> getAllReports() {
        return reportRepository.findAllByOrderByUploadedAtDesc();
    }

    public List<GameResult> getResultsByPlayer(Long playerId) {
        return gameResultRepository.findByPlayerIdOrderBySessionStartTimeDesc(playerId);
    }
}
