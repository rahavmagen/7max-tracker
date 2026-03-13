package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerService playerService;

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

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<Transaction>> getTransactions(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth) && !id.equals(getPlayerId(auth))) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(playerService.getPlayerTransactions(id));
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
