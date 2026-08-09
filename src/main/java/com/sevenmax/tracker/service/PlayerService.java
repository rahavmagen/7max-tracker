package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.AdminExpense;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.AdminExpenseRepository;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import com.sevenmax.tracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final AdminExpenseRepository adminExpenseRepository;
    private final PasswordEncoder passwordEncoder;

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
        // club_player_id has a UNIQUE index. Postgres allows many NULLs but only one ''.
        // A blank ClubGG Player ID from the Add-Player form must become NULL, otherwise the
        // second player added without a club ID collides with the first (misreported to the
        // UI as "username might already exist").
        if (player.getClubPlayerId() != null && player.getClubPlayerId().isBlank()) {
            player.setClubPlayerId(null);
        }
        if (player.getUsername() != null) player.setUsername(player.getUsername().trim());
        Player saved = playerRepository.save(player);
        createUserForPlayer(saved);
        return saved;
    }

    private void createUserForPlayer(Player player) {
        if (player.getUsername() == null || player.getUsername().isBlank()) return;
        // A player should never end up with two login accounts. Checking by player_id catches the
        // case that broke before: this exact player already has a login, just under a username
        // that now differs in casing/spelling from the current one. The case-insensitive username
        // check additionally guards against colliding with a *different* player's login.
        if (player.getId() != null && userRepository.existsByPlayerId(player.getId())) return;
        if (userRepository.findByUsernameIgnoreCase(player.getUsername()).isPresent()) return;
        String rawPassword = (player.getPhone() != null && !player.getPhone().isBlank())
                ? player.getPhone().replaceAll("[^0-9]", "")
                : "123456";
        if (rawPassword.isBlank()) rawPassword = "123456";
        User u = new User();
        u.setUsername(player.getUsername());
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setRole(User.Role.PLAYER);
        u.setPlayer(player);
        u.setMustChangePassword(true);
        u.setActive(true);
        userRepository.save(u);
        log.info("Created user account for player '{}' (password: {})", player.getUsername(),
                player.getPhone() != null && !player.getPhone().isBlank() ? "phone digits" : "123456");
    }

    public Player updatePlayer(Long id, Player updated) {
        Player player = getPlayer(id);
        player.setFullName(updated.getFullName());
        String oldPhone = player.getPhone();
        player.setPhone(updated.getPhone());
        player.setClubPlayerId(updated.getClubPlayerId());
        player.setCreditTotal(updated.getCreditTotal());
        player.setActive(updated.getActive());
        // Agent system fields
        if (updated.getIsAgent() != null) {
            player.setIsAgent(updated.getIsAgent());
        }
        if (updated.getAgentRakePercentage() != null) {
            player.setAgentRakePercentage(updated.getAgentRakePercentage());
        } else if (Boolean.FALSE.equals(updated.getIsAgent())) {
            // Clear rake % when un-marking as agent
            player.setAgentRakePercentage(null);
        }
        // Rakeback fields
        player.setRakebackPercentage(updated.getRakebackPercentage());
        player.setRakebackSince(updated.getRakebackSince());
        if (updated.getSeeRakeback() != null) {
            player.setSeeRakeback(updated.getSeeRakeback());
        }
        // Satellite backing (PROTOTYPE)
        if (updated.getSatelliteBacked() != null) {
            player.setSatelliteBacked(updated.getSatelliteBacked());
        }
        player.setSatelliteBackedSince(updated.getSatelliteBackedSince());
        Player saved = playerRepository.save(player);
        // If phone changed, update password for users who never logged in
        String newPhone = updated.getPhone();
        boolean phoneChanged = newPhone != null && !newPhone.isBlank() && !newPhone.equals(oldPhone);
        if (phoneChanged) {
            userRepository.findByUsername(player.getUsername()).ifPresent(user -> {
                if (user.getLastLoginAt() == null) {
                    String digits = newPhone.replaceAll("[^0-9]", "");
                    if (!digits.isBlank()) {
                        user.setPasswordHash(passwordEncoder.encode(digits));
                        userRepository.save(user);
                        log.info("Updated password for user '{}' to new phone digits (never logged in)", player.getUsername());
                    }
                }
            });
        }
        return saved;
    }

    /** Enroll/unenroll a player in a backing program ("horse"): SATELLITE or TOURNAMENT.
     *  enabled=false with until=null is an immediate full removal (clears everything). enabled=false
     *  with until set instead schedules an end date: the horse stays enrolled so games before that
     *  date keep counting, but no games on/after it will (see TournamentHorseService/SatelliteBackingService). */
    @Transactional
    public Player setHorse(Long id, String program, LocalDate since, boolean enabled, String gameTypes, LocalDate until) {
        Player p = getPlayer(id);
        if ("SATELLITE".equalsIgnoreCase(program)) {
            if (enabled) {
                p.setSatelliteBacked(true);
                p.setSatelliteBackedSince(since);
                p.setSatelliteBackedUntil(until);
            } else if (until != null) {
                p.setSatelliteBackedUntil(until);
            } else {
                p.setSatelliteBacked(false);
                p.setSatelliteBackedSince(null);
                p.setSatelliteBackedUntil(null);
            }
        } else if ("TOURNAMENT".equalsIgnoreCase(program)) {
            if (enabled) {
                p.setTournamentHorseBacked(true);
                p.setTournamentHorseBackedSince(since);
                p.setTournamentHorseGameTypes(gameTypes);
                p.setTournamentHorseBackedUntil(until);
            } else if (until != null) {
                p.setTournamentHorseBackedUntil(until);
            } else {
                p.setTournamentHorseBacked(false);
                p.setTournamentHorseBackedSince(null);
                p.setTournamentHorseGameTypes(null);
                p.setTournamentHorseBackedUntil(null);
            }
        } else {
            throw new RuntimeException("Unknown backing program: " + program);
        }
        return playerRepository.save(p);
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
        userRepository.findAllByPlayerId(id).forEach(u -> {
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
    public Player updateCredit(Long id, BigDecimal delta, String notes, String createdByUsername) {
        Player player = getPlayer(id);
        BigDecimal newCredit = (player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO).add(delta);
        if (newCredit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                "Credit cannot go negative. Current: " + player.getCreditTotal() + ", delta: " + delta + ", result would be: " + newCredit
            );
        }
        BigDecimal currentChips = player.getCurrentChips() != null ? player.getCurrentChips() : BigDecimal.ZERO;
        player.setCreditTotal(newCredit);
        player.setBalance(currentChips.subtract(newCredit));
        Player saved = playerRepository.save(player);

        // If there's already a pending TRADE: transaction for the same player+amount,
        // confirm it instead of creating a duplicate SCREEN:CREDIT entry.
        Optional<Transaction> existingTrade = transactionRepository
                .findFirstByPlayerIdAndAmountAndPendingConfirmationTrue(player.getId(), delta.abs());
        if (existingTrade.isPresent() && existingTrade.get().getSourceRef() != null
                && existingTrade.get().getSourceRef().startsWith("TRADE:")) {
            existingTrade.get().setPendingConfirmation(false);
            transactionRepository.save(existingTrade.get());
            log.info("Manual credit confirmed existing TRADE: pending id={} player={} amount={}",
                    existingTrade.get().getId(), player.getUsername(), delta.abs());
        } else {
            Transaction tx = new Transaction();
            tx.setPlayer(player);
            tx.setType(delta.compareTo(BigDecimal.ZERO) >= 0 ? Transaction.Type.DEPOSIT : Transaction.Type.WITHDRAWAL);
            tx.setAmount(delta.abs());
            tx.setNotes("Manual Credit" + (notes != null ? " - " + notes : ""));
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(createdByUsername);
            tx.setPendingConfirmation(false);
            tx.setSourceRef("SCREEN:CREDIT");
            transactionRepository.save(tx);
        }

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

        // Also record in admin expenses tab
        AdminExpense exp = new AdminExpense();
        exp.setAdminUsername("Wheel");
        exp.setAmount(amount);
        exp.setNotes("Wheel - " + player.getUsername() + (notes != null && !notes.isBlank() ? " (" + notes + ")" : ""));
        exp.setExpenseDate(LocalDate.now());
        exp.setCreatedBy(createdByUsername);
        adminExpenseRepository.save(exp);

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
        userRepository.deleteAll(userRepository.findAllByPlayerId(id));
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
