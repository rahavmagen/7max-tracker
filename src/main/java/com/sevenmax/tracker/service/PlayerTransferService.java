package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.BankAccount;
import com.sevenmax.tracker.entity.BankTransaction;
import com.sevenmax.tracker.entity.GameResult;
import com.sevenmax.tracker.entity.GameSession;
import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.PlayerTransfer;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.BankAccountRepository;
import com.sevenmax.tracker.repository.BankTransactionRepository;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.GameSessionRepository;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerTransferService {

    private final PlayerTransferRepository transferRepository;
    private final PlayerRepository playerRepository;
    private final BankAccountRepository bankAccountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final GameSessionRepository gameSessionRepository;
    private final GameResultRepository gameResultRepository;
    private final ImportSummaryRepository importSummaryRepository;
    private final BankTransactionRepository bankTransactionRepository;

    @Transactional
    public PlayerTransfer createTransfer(Long fromPlayerId, Long fromBankAccountId, Long toPlayerId,
            Long toBankAccountId, BigDecimal amount, Transaction.Method method, String notes,
            String createdBy, String fromAdminUsername, String toAdminUsername) {
        Player fromPlayer = fromPlayerId != null ? playerRepository.findById(fromPlayerId).orElse(null) : null;
        Player toPlayer = toPlayerId != null ? playerRepository.findById(toPlayerId).orElse(null) : null;
        BankAccount fromBankAccount = fromBankAccountId != null ? bankAccountRepository.findById(fromBankAccountId).orElse(null) : null;
        BankAccount toBankAccount = toBankAccountId != null ? bankAccountRepository.findById(toBankAccountId).orElse(null) : null;

        PlayerTransfer transfer = new PlayerTransfer();
        transfer.setFromPlayer(fromPlayer);
        transfer.setToPlayer(toPlayer);
        transfer.setFromBankAccount(fromBankAccount);
        transfer.setToBankAccount(toBankAccount);
        transfer.setAmount(amount);
        transfer.setMethod(method);
        transfer.setNotes(notes);
        transfer.setTransferDate(LocalDate.now());
        transfer.setCreatedByUsername(createdBy);
        transfer.setFromAdminUsername(fromAdminUsername);
        transfer.setToAdminUsername(toAdminUsername);

        transfer = transferRepository.save(transfer);
        String sourceRef = "TRANSFER:" + transfer.getId();

        String toLabel = toPlayer != null ? toPlayer.getUsername() : (toBankAccount != null ? toBankAccount.getName() : "CLUB");
        String fromLabel = fromPlayer != null ? fromPlayer.getUsername() : (fromBankAccount != null ? fromBankAccount.getName() : "CLUB");

        // FROM player transfers → PAYMENT → balance increases (debt reduces toward 0)
        if (fromPlayer != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(fromPlayer);
            tx.setType(Transaction.Type.PAYMENT);
            tx.setAmount(amount);
            tx.setMethod(method);
            tx.setNotes("Transfer to " + toLabel + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdBy);
            tx.setSourceRef(sourceRef);
            transactionService.addTransaction(tx);
        }

        // TO player receives → CREDIT → balance decreases (credit reduces toward 0)
        if (toPlayer != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(toPlayer);
            tx.setType(Transaction.Type.CREDIT);
            tx.setAmount(amount);
            tx.setMethod(method);
            tx.setNotes("Transfer from " + fromLabel + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdBy);
            tx.setSourceRef(sourceRef);
            transactionService.addTransaction(tx);
        }

        // Update bank balance only when club/bank account is involved AND it's a bank transfer (no admin wallet attribution)
        boolean hasAdminAttribution = fromAdminUsername != null || toAdminUsername != null;
        boolean toBank = !hasAdminAttribution && (toBankAccount != null || (toPlayer == null && fromPlayer != null));
        boolean fromBank = !hasAdminAttribution && (fromBankAccount != null || (fromPlayer == null && toPlayer != null));
        if (toBank || fromBank) {
            // Record in bank_transactions so the wallet page balance stays in sync
            BankTransaction bt = new BankTransaction();
            bt.setAmount(toBank ? amount : amount.negate());
            bt.setTransactionDate(LocalDate.now());
            bt.setNotes("Transfer: " + fromLabel + " → " + toLabel + (notes != null ? " - " + notes : ""));
            bt.setCreatedBy(createdBy);
            bankTransactionRepository.save(bt);
        }

        return transfer;
    }

    @Transactional
    public PlayerTransfer createPayment(Long fromPlayerId, Long toPlayerId, BigDecimal amount,
                                        Transaction.Method method, String notes, String createdBy) {
        Player fromPlayer = fromPlayerId != null ? playerRepository.findById(fromPlayerId).orElse(null) : null;
        Player toPlayer = toPlayerId != null ? playerRepository.findById(toPlayerId).orElse(null) : null;

        PlayerTransfer transfer = new PlayerTransfer();
        transfer.setFromPlayer(fromPlayer);
        transfer.setToPlayer(toPlayer);
        transfer.setAmount(amount);
        transfer.setMethod(method);
        transfer.setNotes(notes);
        transfer.setTransferDate(LocalDate.now());
        transfer.setCreatedByUsername(createdBy);
        transfer = transferRepository.save(transfer);
        String sourceRef = "PAYMENT:" + transfer.getId();

        log.info("PAYMENT: from={} (balance={}) to={} (balance={}) amount={}",
            fromPlayer != null ? fromPlayer.getUsername() : "null",
            fromPlayer != null ? fromPlayer.getBalance() : "null",
            toPlayer != null ? toPlayer.getUsername() : "null",
            toPlayer != null ? toPlayer.getBalance() : "null",
            amount);

        // Payer → PAYMENT → balance increases toward 0 (debt cleared)
        if (fromPlayer != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(fromPlayer);
            tx.setType(Transaction.Type.PAYMENT);
            tx.setAmount(amount);
            tx.setMethod(method);
            tx.setNotes("Payment to " + (toPlayer != null ? toPlayer.getUsername() : "CLUB") + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdBy);
            tx.setSourceRef(sourceRef);
            transactionService.addTransaction(tx);
        }

        log.info("PAYMENT: after payer tx, {}.balance={}", fromPlayer != null ? fromPlayer.getUsername() : "null", fromPlayer != null ? fromPlayer.getBalance() : "null");

        // Receiver → CREDIT → balance decreases toward 0 (claim reduced)
        if (toPlayer != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(toPlayer);
            tx.setType(Transaction.Type.CREDIT);
            tx.setAmount(amount);
            tx.setMethod(method);
            tx.setNotes("Received from " + (fromPlayer != null ? fromPlayer.getUsername() : "CLUB") + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdBy);
            tx.setSourceRef(sourceRef);
            transactionService.addTransaction(tx);
        }

        log.info("PAYMENT: after receiver tx, {}.balance={}", toPlayer != null ? toPlayer.getUsername() : "null", toPlayer != null ? toPlayer.getBalance() : "null");

        return transfer;
    }

    @Transactional
    public PlayerTransfer updateTransfer(Long id, BigDecimal newAmount, String newNotes, Transaction.Method newMethod) {
        PlayerTransfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));

        BigDecimal diff = newAmount.subtract(transfer.getAmount());
        String sourceRef = "TRANSFER:" + id;
        List<Transaction> linked = transactionRepository.findBySourceRef(sourceRef);

        if (!linked.isEmpty()) {
            for (Transaction tx : linked) {
                transactionService.updateTransaction(tx.getId(), newAmount, tx.getNotes());
                if (newMethod != null) {
                    tx.setMethod(newMethod);
                    transactionRepository.save(tx);
                }
            }
        } else {
            // Legacy transfer without sourceRef: adjust balances directly
            Player fromPlayer = transfer.getFromPlayer();
            Player toPlayer = transfer.getToPlayer();
            if (fromPlayer != null) {
                fromPlayer.setBalance(fromPlayer.getBalance().subtract(diff));
                playerRepository.save(fromPlayer);
            }
            if (toPlayer != null) {
                toPlayer.setBalance(toPlayer.getBalance().add(diff));
                playerRepository.save(toPlayer);
            }
        }

        transfer.setAmount(newAmount);
        if (newNotes != null) transfer.setNotes(newNotes);
        if (newMethod != null) transfer.setMethod(newMethod);
        return transferRepository.save(transfer);
    }

    public List<PlayerTransfer> getPendingTransfers() {
        return transferRepository.findByConfirmedFalseOrderByCreatedAtDesc();
    }

    public List<Map<String, Object>> getAllPending() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();

        // Credits and promotions (screen-created, pending confirmation)
        transactionRepository.findByPendingConfirmationTrueOrderByCreatedAtDesc().forEach(tx -> {
            Map<String, Object> item = new HashMap<>();
            String ref = tx.getSourceRef();
            item.put("pendingType", ref != null && ref.equals("SCREEN:CHIP_PROMO") ? "CHIP_PROMO"
                    : ref != null && ref.startsWith("SCREEN:PROMO") ? "PROMOTION"
                    : ref != null && ref.startsWith("TRADE:") ? "XLS_UNMATCHED"
                    : ref != null && ref.startsWith("EXPENSE:") ? "EXPENSE_REPAYMENT"
                    : "CREDIT");
            item.put("transactionType", tx.getType() != null ? tx.getType().name() : "CREDIT");
            item.put("id", tx.getId());
            item.put("transactionDate", tx.getTransactionDate() != null ? tx.getTransactionDate().toString() : null);
            item.put("playerId", tx.getPlayer().getId());
            item.put("playerName", tx.getPlayer().getUsername());
            item.put("playerFullName", tx.getPlayer().getFullName());
            item.put("amount", tx.getAmount());
            item.put("notes", tx.getNotes());
            item.put("createdByUsername", tx.getCreatedByUsername());
            item.put("createdAt", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
            result.add(item);
        });

        // Player transfers
        transferRepository.findByConfirmedFalseOrderByCreatedAtDesc().forEach(t -> {
            Map<String, Object> item = toDto(t);
            item.put("pendingType", "TRANSFER");
            result.add(item);
        });

        // Sort combined list by createdAt desc
        result.sort((a, b) -> {
            String ca = (String) a.get("createdAt");
            String cb = (String) b.get("createdAt");
            if (ca == null && cb == null) return 0;
            if (ca == null) return 1;
            if (cb == null) return -1;
            return cb.compareTo(ca);
        });

        return result;
    }

    @Transactional
    public void confirmTransfer(Long id, String confirmedBy) {
        PlayerTransfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transfer not found"));
        transfer.setConfirmed(true);
        transfer.setConfirmedAt(LocalDateTime.now());
        transfer.setConfirmedBy(confirmedBy);
        transferRepository.save(transfer);
    }

    // Returns the MTT session starting within ±40 min of 21:00 on the given date,
    // with each participant's cost (buyIn + rakePaid).
    public Map<String, Object> getLastNightMtt(LocalDate date) {
        LocalDateTime windowStart = date.atTime(20, 20);
        LocalDateTime windowEnd = date.atTime(21, 40);

        List<GameSession> sessions = gameSessionRepository.findByGameTypeAndStartTimeBetween(
                GameSession.GameType.MTT, windowStart, windowEnd);

        if (sessions.isEmpty()) return null;

        GameSession session = sessions.get(0);
        List<GameResult> results = gameResultRepository.findBySessionId(session.getId());

        Map<String, Object> dto = new HashMap<>();
        dto.put("id", session.getId());
        dto.put("tableName", session.getTableName());
        dto.put("startTime", session.getStartTime().toString());

        List<Map<String, Object>> participants = results.stream().map(r -> {
            Map<String, Object> p = new HashMap<>();
            p.put("playerId", r.getPlayer().getId());
            p.put("username", r.getPlayer().getUsername());
            p.put("fullName", r.getPlayer().getFullName());
            BigDecimal cost = (r.getBuyIn() != null ? r.getBuyIn() : BigDecimal.ZERO)
                    .add(r.getRakePaid() != null ? r.getRakePaid() : BigDecimal.ZERO);
            p.put("cost", cost);
            return p;
        }).collect(Collectors.toList());

        dto.put("participants", participants);
        return dto;
    }

    public Map<String, Object> toDto(PlayerTransfer t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        String fromName = t.getFromPlayer() != null ? t.getFromPlayer().getUsername()
                : (t.getFromBankAccount() != null ? t.getFromBankAccount().getName() : "CLUB");
        String toName = t.getToPlayer() != null ? t.getToPlayer().getUsername()
                : (t.getToBankAccount() != null ? t.getToBankAccount().getName() : "CLUB");
        m.put("fromPlayerName", fromName);
        m.put("fromPlayerFullName", t.getFromPlayer() != null ? t.getFromPlayer().getFullName() : null);
        m.put("fromPlayerId", t.getFromPlayer() != null ? t.getFromPlayer().getId() : null);
        m.put("fromBankAccountId", t.getFromBankAccount() != null ? t.getFromBankAccount().getId() : null);
        m.put("toPlayerName", toName);
        m.put("toPlayerFullName", t.getToPlayer() != null ? t.getToPlayer().getFullName() : null);
        m.put("toPlayerId", t.getToPlayer() != null ? t.getToPlayer().getId() : null);
        m.put("toBankAccountId", t.getToBankAccount() != null ? t.getToBankAccount().getId() : null);
        m.put("amount", t.getAmount());
        m.put("method", t.getMethod());
        m.put("notes", t.getNotes());
        m.put("confirmed", t.getConfirmed());
        m.put("transferDate", t.getTransferDate() != null ? t.getTransferDate().toString() : null);
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        m.put("confirmedAt", t.getConfirmedAt() != null ? t.getConfirmedAt().toString() : null);
        m.put("confirmedBy", t.getConfirmedBy());
        m.put("createdByUsername", t.getCreatedByUsername());
        return m;
    }
}
