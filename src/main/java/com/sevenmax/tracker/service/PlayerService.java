package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;

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
    public void adjustBalance(Player player, BigDecimal amount) {
        player.setBalance(player.getBalance().add(amount));
        playerRepository.save(player);
    }

    @Transactional
    public Player updateCredit(Long id, BigDecimal delta, String notes) {
        Player player = getPlayer(id);
        BigDecimal newCredit = (player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO).add(delta);
        BigDecimal newChips = (player.getCurrentChips() != null ? player.getCurrentChips() : BigDecimal.ZERO).add(delta);
        player.setCreditTotal(newCredit);
        player.setCurrentChips(newChips);
        player.setBalance(newChips.subtract(newCredit));
        return playerRepository.save(player);
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

    public List<Transaction> getPlayerTransactions(Long playerId) {
        return transactionRepository.findByPlayerIdOrderByTransactionDateDesc(playerId);
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
