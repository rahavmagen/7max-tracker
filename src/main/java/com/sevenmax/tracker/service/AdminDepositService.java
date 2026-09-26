package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.event.NewDepositEvent;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Third deposit source alongside Grow/KashCash - triggered directly by an admin/manager/worker,
 * not by a real payment. Unlike Grow/KashCash there's no external payment gateway step to
 * correlate against (no "did the money actually arrive" question to answer asynchronously) - the
 * admin's submit click IS the confirmation, so the Transaction row is created directly, with no
 * separate "Initiated" staging entity the way GrowInitiated/KashcashInitiated exist for the
 * other two.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDepositService {

    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Transaction createDeposit(Long playerId, String newPlayerUsername, BigDecimal amount, String note) {
        if ((playerId == null) == (newPlayerUsername == null)) {
            throw new IllegalArgumentException("Exactly one of playerId or newPlayerUsername must be given");
        }
        if (amount == null || amount.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Minimum deposit is 1");
        }

        Player player = playerId != null
                ? playerRepository.findById(playerId)
                        .orElseThrow(() -> new RuntimeException("Player not found: " + playerId))
                : findOrCreateStubPlayer(newPlayerUsername);

        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.ADMIN_DEPOSIT);
        tx.setMethod(Transaction.Method.MANUAL);
        tx.setAmount(amount);
        tx.setNotes(note);
        tx.setChipsConfirmed(false);
        tx.setTransactionDate(LocalDate.now());
        Transaction saved = transactionService.addTransaction(tx);

        eventPublisher.publishEvent(new NewDepositEvent("ADMIN"));
        log.info("Admin deposit created: player={}, amount={}", player.getUsername(), amount);
        return saved;
    }

    /** Same-day-joiner support: reuses an existing stub/imported player by username if one
     *  already exists (never creates a duplicate), otherwise creates a minimal Player with only
     *  username set - the one field Player actually requires. A future ClubGG XLS import merges
     *  into this same row by username (see ImportService, already confirmed - not this task's
     *  concern). */
    private Player findOrCreateStubPlayer(String username) {
        return playerRepository.findByUsername(username).orElseGet(() -> {
            Player stub = new Player();
            stub.setUsername(username);
            stub.setActive(true);
            stub.setBalance(BigDecimal.ZERO);
            stub.setCreditTotal(BigDecimal.ZERO);
            stub.setCurrentChips(BigDecimal.ZERO);
            stub.setDepositsTotal(BigDecimal.ZERO);
            return playerRepository.save(stub);
        });
    }

    @Transactional
    public Transaction confirmChips(Long transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + transactionId));
        if (tx.getType() != Transaction.Type.ADMIN_DEPOSIT) {
            throw new IllegalArgumentException("Not an admin deposit transaction");
        }
        tx.setChipsConfirmed(true);
        return transactionRepository.save(tx);
    }

    public List<Map<String, Object>> getPending() {
        return transactionRepository.findPendingAdminDeposits()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public Map<String, Object> getHistory(LocalDate from, LocalDate to) {
        List<Transaction> txs;
        if (from != null && to != null) {
            txs = transactionRepository.findAdminDepositsBetween(
                    from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        } else {
            txs = transactionRepository.findAllAdminDeposits();
        }
        List<Map<String, Object>> rows = txs.stream().map(this::toDto).collect(Collectors.toList());
        BigDecimal total = txs.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("rows", rows, "total", total);
    }

    private Map<String, Object> toDto(Transaction tx) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", tx.getId());
        m.put("playerId", tx.getPlayer().getId());
        m.put("username", tx.getPlayer().getUsername());
        m.put("fullName", tx.getPlayer().getFullName());
        m.put("amount", tx.getAmount());
        m.put("note", tx.getNotes());
        m.put("chipsConfirmed", Boolean.TRUE.equals(tx.getChipsConfirmed()));
        m.put("date", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
        return m;
    }
}
