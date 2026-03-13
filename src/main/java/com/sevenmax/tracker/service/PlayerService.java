package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

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
        return playerRepository.findByClubPlayerId(clubPlayerId).orElse(null);
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

    public List<Transaction> getPlayerTransactions(Long playerId) {
        return transactionRepository.findByPlayerIdOrderByTransactionDateDesc(playerId);
    }
}
