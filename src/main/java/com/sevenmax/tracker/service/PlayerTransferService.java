package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.GameResult;
import com.sevenmax.tracker.entity.GameSession;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.PlayerTransfer;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.GameSessionRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlayerTransferService {

    private final PlayerTransferRepository transferRepository;
    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final GameSessionRepository gameSessionRepository;
    private final GameResultRepository gameResultRepository;

    @Transactional
    public PlayerTransfer createTransfer(Long fromPlayerId, Long toPlayerId, BigDecimal amount,
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
        String sourceRef = "TRANSFER:" + transfer.getId();

        // Sender pays → CREDIT (shown as "Payment")
        if (fromPlayer != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(fromPlayer);
            tx.setType(Transaction.Type.CREDIT);
            tx.setAmount(amount);
            tx.setMethod(method);
            tx.setNotes("Payment to " + (toPlayer != null ? toPlayer.getUsername() : "CLUB") + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdBy);
            tx.setSourceRef(sourceRef);
            transactionService.addTransaction(tx);
        }

        // Receiver gets money → REPAYMENT (shown as "Cashout")
        if (toPlayer != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(toPlayer);
            tx.setType(Transaction.Type.REPAYMENT);
            tx.setAmount(amount);
            tx.setMethod(method);
            tx.setNotes("Cashout from " + (fromPlayer != null ? fromPlayer.getUsername() : "CLUB") + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdBy);
            tx.setSourceRef(sourceRef);
            transactionService.addTransaction(tx);
        }

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
            item.put("pendingType", ref != null && ref.startsWith("SCREEN:PROMO") ? "PROMOTION" : "CREDIT");
            item.put("id", tx.getId());
            item.put("transactionDate", tx.getTransactionDate() != null ? tx.getTransactionDate().toString() : null);
            item.put("playerId", tx.getPlayer().getId());
            item.put("playerName", tx.getPlayer().getUsername());
            item.put("playerFullName", tx.getPlayer().getFullName());
            item.put("amount", tx.getAmount());
            item.put("notes", tx.getNotes());
            item.put("createdByUsername", tx.getCreatedByUsername());
            result.add(item);
        });

        // Player transfers
        transferRepository.findByConfirmedFalseOrderByCreatedAtDesc().forEach(t -> {
            Map<String, Object> item = toDto(t);
            item.put("pendingType", "TRANSFER");
            result.add(item);
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
        m.put("fromPlayerName", t.getFromPlayer() != null ? t.getFromPlayer().getUsername() : "CLUB");
        m.put("fromPlayerFullName", t.getFromPlayer() != null ? t.getFromPlayer().getFullName() : null);
        m.put("fromPlayerId", t.getFromPlayer() != null ? t.getFromPlayer().getId() : null);
        m.put("toPlayerName", t.getToPlayer() != null ? t.getToPlayer().getUsername() : "CLUB");
        m.put("toPlayerFullName", t.getToPlayer() != null ? t.getToPlayer().getFullName() : null);
        m.put("toPlayerId", t.getToPlayer() != null ? t.getToPlayer().getId() : null);
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
