package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerService playerService;
    private final PlayerRepository playerRepository;
    private final GameResultRepository gameResultRepository;

    @GetMapping
    public ResponseEntity<List<Player>> getAllPlayers(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(playerService.getAllPlayers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Player> getPlayer(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth) && !id.equals(getPlayerId(auth))) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(playerService.getPlayer(id));
    }

    @PostMapping
    public ResponseEntity<Player> createPlayer(@RequestBody Player player, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(playerService.createPlayer(player));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Player> updatePlayer(@PathVariable Long id, @RequestBody Player player, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(playerService.updatePlayer(id, player));
    }

    @PatchMapping("/{id}/credit")
    public ResponseEntity<Player> updateCredit(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        BigDecimal amount = new BigDecimal(body.get("delta").toString());
        String notes = body.containsKey("notes") ? body.get("notes").toString() : null;
        return ResponseEntity.ok(playerService.updateCredit(id, amount, notes));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<Player> addDeposit(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String notes = body.containsKey("notes") ? body.get("notes").toString() : null;
        return ResponseEntity.ok(playerService.addDeposit(id, amount, notes, auth.getName()));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<Transaction>> getTransactions(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth) && !id.equals(getPlayerId(auth))) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(playerService.getPlayerTransactions(id));
    }

    // Cleanup: delete players whose username contains Hebrew characters and have no game results
    @DeleteMapping("/cleanup-hebrew")
    public ResponseEntity<Map<String, Object>> cleanupHebrewPlayers(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        Set<Long> withResults = new HashSet<>(gameResultRepository.findPlayerIdsWithGameResults());
        List<Player> toDelete = playerRepository.findAll().stream()
                .filter(p -> !withResults.contains(p.getId()))
                .filter(p -> p.getUsername() != null && p.getUsername().chars()
                        .anyMatch(c -> c >= 0x05D0 && c <= 0x05EA))
                .collect(Collectors.toList());
        playerRepository.deleteAll(toDelete);
        return ResponseEntity.ok(Map.of("deleted", toDelete.size(),
                "names", toDelete.stream().map(Player::getUsername).collect(Collectors.toList())));
    }

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }

    @SuppressWarnings("unchecked")
    private Long getPlayerId(Authentication auth) {
        if (auth.getDetails() instanceof Map<?, ?> details) {
            Object v = details.get("playerId");
            if (v instanceof Long l) return l;
            if (v instanceof Number n) return n.longValue();
        }
        return -1L;
    }
}
