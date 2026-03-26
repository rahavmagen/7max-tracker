package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import com.sevenmax.tracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final GameResultRepository gameResultRepository;
    private final PlayerTransferRepository playerTransferRepository;

    public List<Player> getAllPlayers() {
        return playerRepository.findAll();
    }

    public Player getPlayer(Long id) {
        return playerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Player not found: " + id));
    }

    public Player getPlayerByClubId(String clubPlayerId) {
        return playerRepository.findByClubPlayerIdSafe(clubPlayerId).stream().findFirst().orElse(null);
    }

    public Player createPlayer(Player player) {
        return playerRepository.save(player);
    }

    public Player updatePlayer(Long id, Player updated) {
        Player player = getPlayer(id);
        player.setFullName(updated.getFullName());
        player.setPhone(updated.getPhone());
        player.setClubPlayerId(updated.getClubPlayerId());
        player.setCreditTotal(updated.getCreditTotal());
        player.setActive(updated.getActive());
        return playerRepository.save(player);
    }

    @Transactional
    public Player renameUsername(Long id, String newUsername) {
        if (newUsername == null || newUsername.isBlank()) throw new RuntimeException("Username cannot be empty");
        if (playerRepository.findByUsernameCaseInsensitive(newUsername).stream().anyMatch(p -> !p.getId().equals(id))) {
            throw new RuntimeException("Username already taken");
        }
        Player player = getPlayer(id);
        player.setUsername(newUsername);
        playerRepository.save(player);
        userRepository.findByPlayerId(id).ifPresent(u -> {
            u.setUsername(newUsername);
            userRepository.save(u);
        });
        return player;
    }

    @Transactional
    public void adjustBalance(Player player, BigDecimal amount) {
        player.setBalance(player.getBalance().add(amount));
        playerRepository.save(player);
    }

    @Transactional
    public Player setBalance(Long id, BigDecimal newBalance, String notes, String createdByUsername) {
        Player player = getPlayer(id);
        BigDecimal oldBalance = player.getBalance() != null ? player.getBalance() : BigDecimal.ZERO;
        BigDecimal diff = newBalance.subtract(oldBalance);
        player.setBalance(newBalance);
        Player saved = playerRepository.save(player);

        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.DEPOSIT); // neutral audit record
        tx.setAmount(diff.abs());
        tx.setNotes("Manual Balance Adjustment" + (notes != null && !notes.isBlank() ? " - " + notes : "")
                + " (old: " + oldBalance + ", new: " + newBalance + ")");
        tx.setTransactionDate(LocalDate.now());
        tx.setCreatedByUsername(createdByUsername);
        tx.setSourceRef("SCREEN:MANUAL_BALANCE");
        transactionRepository.save(tx);

        return saved;
    }

    @Transactional
    public Player updateCredit(Long id, BigDecimal delta, String notes, String createdByUsername, boolean updateChips) {
        Player player = getPlayer(id);
        BigDecimal newCredit = (player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO).add(delta);
        BigDecimal currentChips = player.getCurrentChips() != null ? player.getCurrentChips() : BigDecimal.ZERO;
        BigDecimal newChips = updateChips ? currentChips.add(delta) : currentChips;
        player.setCreditTotal(newCredit);
        if (updateChips) player.setCurrentChips(newChips);
        player.setBalance(newChips.subtract(newCredit));
        Player saved = playerRepository.save(player);

        // Save a pending Transaction record for reconciliation against XLS upload
        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(delta.compareTo(BigDecimal.ZERO) >= 0 ? Transaction.Type.DEPOSIT : Transaction.Type.WITHDRAWAL);
        tx.setAmount(delta.abs());
        tx.setNotes("Manual Credit" + (notes != null ? " - " + notes : ""));
        tx.setTransactionDate(LocalDate.now());
        tx.setCreatedByUsername(createdByUsername);
        tx.setPendingConfirmation(true);
        tx.setSourceRef("SCREEN:CREDIT");
        transactionRepository.save(tx);

        return saved;
    }

    @Transactional
    public Player addDeposit(Long id, BigDecimal amount, String notes, String createdByUsername) {
        Player player = getPlayer(id);
        BigDecimal current = player.getDepositsTotal() != null ? player.getDepositsTotal() : BigDecimal.ZERO;
        player.setDepositsTotal(current.add(amount));
        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.DEPOSIT);
        tx.setAmount(amount);
        tx.setNotes(notes);
        tx.setTransactionDate(java.time.LocalDate.now());
        tx.setCreatedByUsername(createdByUsername);
        transactionRepository.save(tx);
        return playerRepository.save(player);
    }

    @Transactional
    public Player addWheelExpense(Long id, BigDecimal amount, String notes, String createdByUsername) {
        Player player = getPlayer(id);
        BigDecimal newChips = (player.getCurrentChips() != null ? player.getCurrentChips() : BigDecimal.ZERO).add(amount);
        player.setCurrentChips(newChips);
        BigDecimal credit = player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO;
        player.setBalance(newChips.subtract(credit));
        Player saved = playerRepository.save(player);

        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.WHEEL_EXPENSE);
        tx.setAmount(amount);
        tx.setNotes("Wheel" + (notes != null && !notes.isBlank() ? " - " + notes : ""));
        tx.setTransactionDate(LocalDate.now());
        tx.setCreatedByUsername(createdByUsername);
        tx.setSourceRef("SCREEN:WHEEL");
        transactionRepository.save(tx);

        return saved;
    }

    public List<Transaction> getPlayerTransactions(Long playerId) {
        return transactionRepository.findByPlayerIdOrderByTransactionDateDesc(playerId);
    }

    @Transactional
    public void deletePlayer(Long id) {
        Player player = getPlayer(id);
        transactionRepository.deleteAll(transactionRepository.findByPlayerIdOrderByTransactionDateDesc(id));
        gameResultRepository.deleteAll(gameResultRepository.findByPlayerIdOrderBySessionStartTimeDesc(id));
        playerTransferRepository.deleteAll(playerTransferRepository.findByFromPlayerIdOrToPlayerId(id, id));
        userRepository.findByPlayerId(id).ifPresent(userRepository::delete);
        playerRepository.delete(player);
        log.info("Deleted player id={} username={}", id, player.getUsername());
    }

    /**
     * Find player by username: exact case-insensitive → fuzzy (strips spaces/underscores/hyphens) → alphanumeric (strips ALL special chars like !).
     */
    public Optional<Player> findPlayerByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        List<Player> exact = playerRepository.findByUsernameCaseInsensitive(username);
        if (!exact.isEmpty()) return Optional.of(exact.get(0));
        List<Player> fuzzy = playerRepository.findByUsernameFuzzy(username);
        if (!fuzzy.isEmpty()) {
            log.warn("Fuzzy username match: '{}' -> '{}'", username, fuzzy.get(0).getUsername());
            return Optional.of(fuzzy.get(0));
        }
        List<Player> alphanum = playerRepository.findByUsernameAlphanumeric(username);
        if (!alphanum.isEmpty()) {
            log.warn("Alphanumeric username match: '{}' -> '{}'", username, alphanum.get(0).getUsername());
            return Optional.of(alphanum.get(0));
        }
        return Optional.empty();
    }
}
