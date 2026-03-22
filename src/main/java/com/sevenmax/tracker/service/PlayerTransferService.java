package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.PlayerTransfer;
import com.sevenmax.tracker.entity.Transaction;
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

@Service
@RequiredArgsConstructor
public class PlayerTransferService {

    private final PlayerTransferRepository transferRepository;
    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;

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

        // Register as WITHDRAWAL for the sender (if not CLUB)
        if (fromPlayer != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(fromPlayer);
            tx.setType(Transaction.Type.WITHDRAWAL);
            tx.setAmount(amount);
            tx.setMethod(method);
            tx.setNotes("Transfer to " + (toPlayer != null ? toPlayer.getUsername() : "CLUB") + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdBy);
            transactionService.addTransaction(tx);
        }

        // Register as DEPOSIT for the receiver (if not CLUB)
        if (toPlayer != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(toPlayer);
            tx.setType(Transaction.Type.DEPOSIT);
            tx.setAmount(amount);
            tx.setMethod(method);
            tx.setNotes("Transfer from " + (fromPlayer != null ? fromPlayer.getUsername() : "CLUB") + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdBy);
            transactionService.addTransaction(tx);
        }

        return transfer;
    }

    public List<PlayerTransfer> getPendingTransfers() {
        return transferRepository.findByConfirmedFalseOrderByCreatedAtDesc();
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
