package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.service.PlayerService;
import com.sevenmax.tracker.service.TournamentHorseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private final UserRepository userRepository;
    private final com.sevenmax.tracker.repository.PlayerNameHistoryRepository playerNameHistoryRepository;
    private final TournamentHorseService tournamentHorseService;
    private final com.sevenmax.tracker.repository.PlayerRakebackRepository playerRakebackRepository;

    @GetMapping
    public ResponseEntity<List<Player>> getAllPlayers(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(playerService.getAllPlayers());
    }

@GetMapping("/active")
    public ResponseEntity<List<Map<String, Object>>> getActivePlayers() {
        List<com.sevenmax.tracker.entity.Player> all = playerRepository.findAll();
        Map<Long, String> agentNames = all.stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsAgent()))
            .collect(java.util.stream.Collectors.toMap(
                com.sevenmax.tracker.entity.Player::getId,
                com.sevenmax.tracker.entity.Player::getUsername));
        List<Map<String, Object>> result = all.stream()
            .map(p -> {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", p.getId());
                m.put("username", p.getUsername());
                m.put("fullName", p.getFullName() != null ? p.getFullName() : "");
                m.put("agentUsername", p.getAgentId() != null ? agentNames.getOrDefault(p.getAgentId(), "") : "");
                return m;
            })
            .sorted((a, b) -> a.get("username").toString().compareToIgnoreCase(b.get("username").toString()))
            .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Inactive players (no games in the last `days`, default 30) whose balance sits outside
     *  [-5, +5] - either they owe the club, or the club owes them. */
    @GetMapping("/inactive-balance")
    public ResponseEntity<List<Map<String, Object>>> getInactivePlayersBalance(
            @RequestParam(required = false) Integer days, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        int lookbackDays = days != null ? days : 30;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        List<Object[]> rows = gameResultRepository.findInactivePlayersWithBalanceOutsideRange(cutoff, new BigDecimal("5"));
        List<Map<String, Object>> result = rows.stream().map(r -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("username", r[1]);
            m.put("fullName", r[2]);
            m.put("balance", r[3]);
            m.put("lastPlayed", r[4] != null ? r[4].toString() : null);
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stale")
    public ResponseEntity<List<Map<String, String>>> getStalePlayers() {
        List<Map<String, String>> stale = playerRepository.findAll().stream()
            .filter(p -> Boolean.TRUE.equals(p.getChipsStale()))
            .map(p -> {
                Map<String, String> info = new java.util.LinkedHashMap<>();
                info.put("id", String.valueOf(p.getId()));
                info.put("username", p.getUsername());
                info.put("fullName", p.getFullName() != null ? p.getFullName() : "");
                info.put("clubPlayerId", p.getClubPlayerId() != null ? p.getClubPlayerId() : "");
                return info;
            })
            .collect(Collectors.toList());
        return ResponseEntity.ok(stale);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Player> getPlayer(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth) && !id.equals(getPlayerId(auth)) && !isAgentOfPlayer(auth, id)) {
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

    /** Enroll/unenroll a player in a backing "program" (horse): SATELLITE or TOURNAMENT.
     *  TOURNAMENT also takes gameTypes: a comma-separated list of GameSession.GameType names
     *  counted toward that horse's win/loss. An optional "until" (exclusive end date) schedules
     *  removal instead of clearing enrollment immediately - games on/after that date stop counting,
     *  but the horse and everything before the cutoff stays in the report. */
    @PatchMapping("/{id}/horse")
    public ResponseEntity<?> setHorse(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        String program = body.get("program") != null ? body.get("program").toString() : "SATELLITE";
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(body.get("enabled").toString());
        java.time.LocalDate since = (body.get("since") != null && !body.get("since").toString().isBlank())
                ? java.time.LocalDate.parse(body.get("since").toString()) : java.time.LocalDate.now();
        java.time.LocalDate until = (body.get("until") != null && !body.get("until").toString().isBlank())
                ? java.time.LocalDate.parse(body.get("until").toString()) : null;
        String gameTypes = body.get("gameTypes") != null ? body.get("gameTypes").toString() : null;
        try {
            return ResponseEntity.ok(playerService.setHorse(id, program, since, enabled, gameTypes, until));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Record a credit/loss ledger entry for a Tournament Horse (like a write-off, but tied to
     *  that horse's running deficit). Body: { amount, notes, date }. */
    @PostMapping("/{id}/horse-transaction")
    public ResponseEntity<?> addHorseTransaction(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        if (body.get("amount") == null) return ResponseEntity.badRequest().body(Map.of("error", "amount is required"));
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        java.time.LocalDate date = (body.get("date") != null && !body.get("date").toString().isBlank())
                ? java.time.LocalDate.parse(body.get("date").toString()) : null;
        String createdBy = auth != null ? auth.getName() : null;
        return ResponseEntity.ok(tournamentHorseService.addTransaction(id, amount, notes, date, createdBy));
    }

    @GetMapping("/{id}/horse-transactions")
    public ResponseEntity<?> getHorseTransactions(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(tournamentHorseService.getTransactions(id));
    }

    /** Per-game-type rakeback deals for a player. */
    @GetMapping("/{id}/rakeback")
    public ResponseEntity<?> getRakebackDeals(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth) && !id.equals(getPlayerId(auth))) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(playerRakebackRepository.findByPlayerIdOrderByGameTypeAsc(id));
    }

    /** Add a rakeback deal: { gameType, percentage (fraction, e.g. 0.30), startDate }. */
    @PostMapping("/{id}/rakeback")
    public ResponseEntity<?> addRakebackDeal(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        if (body.get("gameType") == null || body.get("gameType").toString().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "gameType is required"));
        if (body.get("percentage") == null)
            return ResponseEntity.badRequest().body(Map.of("error", "percentage is required"));
        com.sevenmax.tracker.entity.PlayerRakeback d = new com.sevenmax.tracker.entity.PlayerRakeback();
        d.setPlayerId(id);
        d.setGameType(body.get("gameType").toString());
        d.setPercentage(new BigDecimal(body.get("percentage").toString()));
        d.setStartDate((body.get("startDate") != null && !body.get("startDate").toString().isBlank())
                ? java.time.LocalDate.parse(body.get("startDate").toString()) : java.time.LocalDate.now());
        return ResponseEntity.ok(playerRakebackRepository.save(d));
    }

    /** Edit an existing rakeback deal: { gameType?, percentage?, startDate? }. */
    @PutMapping("/{id}/rakeback/{dealId}")
    public ResponseEntity<?> updateRakebackDeal(@PathVariable Long id, @PathVariable Long dealId,
                                                @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        com.sevenmax.tracker.entity.PlayerRakeback d = playerRakebackRepository.findById(dealId).orElse(null);
        if (d == null || !d.getPlayerId().equals(id)) return ResponseEntity.badRequest().body(Map.of("error", "deal not found"));
        if (body.get("gameType") != null && !body.get("gameType").toString().isBlank()) d.setGameType(body.get("gameType").toString());
        if (body.get("percentage") != null) d.setPercentage(new BigDecimal(body.get("percentage").toString()));
        if (body.get("startDate") != null && !body.get("startDate").toString().isBlank())
            d.setStartDate(java.time.LocalDate.parse(body.get("startDate").toString()));
        return ResponseEntity.ok(playerRakebackRepository.save(d));
    }

    @DeleteMapping("/{id}/rakeback/{dealId}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> deleteRakebackDeal(@PathVariable Long id, @PathVariable Long dealId, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        playerRakebackRepository.deleteByIdAndPlayerId(dealId, id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PatchMapping("/{id}/username")
    public ResponseEntity<?> renameUsername(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        Object val = body.get("username");
        if (val == null || val.toString().isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "username is required"));
        try {
            return ResponseEntity.ok(playerService.renameUsername(id, val.toString().trim()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/balance")
    public ResponseEntity<?> setBalance(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        Object balVal = body.get("balance");
        if (balVal == null) return ResponseEntity.badRequest().body(Map.of("error", "balance is required"));
        BigDecimal newBalance = new BigDecimal(balVal.toString());
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        String username = auth != null ? auth.getName() : null;
        return ResponseEntity.ok(playerService.setBalance(id, newBalance, notes, username));
    }

    @PatchMapping("/{id}/credit")
    public ResponseEntity<?> updateCredit(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        Object deltaVal = body.get("delta");
        if (deltaVal == null) return ResponseEntity.badRequest().body(Map.of("error", "delta is required"));
        BigDecimal amount = new BigDecimal(deltaVal.toString());
        String notes = (body.get("notes") != null) ? body.get("notes").toString() : null;
        String username = auth != null ? auth.getName() : null;
        try {
            return ResponseEntity.ok(playerService.updateCredit(id, amount, notes, username));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/wheel-expense")
    public ResponseEntity<?> addWheelExpense(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        return ResponseEntity.ok(playerService.addWheelExpense(id, amount, notes, auth.getName()));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<Player> addDeposit(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        return ResponseEntity.ok(playerService.addDeposit(id, amount, notes, auth.getName()));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<Transaction>> getTransactions(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth) && !id.equals(getPlayerId(auth)) && !isAgentOfPlayer(auth, id)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(playerService.getPlayerTransactions(id));
    }

    /** Admin: past nicknames for this player (name changes detected from ClubGG reports) */
    @GetMapping("/{id}/name-history")
    public ResponseEntity<?> getNameHistory(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(playerNameHistoryRepository.findByPlayerIdOrderByChangedAtDesc(id));
    }

    @GetMapping("/{id}/login-stats")
    public ResponseEntity<Map<String, Object>> getLoginStats(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        Map<String, Object> m = new java.util.HashMap<>();
        userRepository.findAllByPlayerId(id).stream()
                .max(java.util.Comparator.comparing(u -> u.getLastLoginAt(), java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .ifPresent(u -> {
                    m.put("loginCount", u.getLoginCount() != null ? u.getLoginCount() : 0);
                    m.put("lastLoginAt", u.getLastLoginAt() != null ? u.getLastLoginAt().toString() : null);
                });
        return ResponseEntity.ok(m);
    }

    @PatchMapping("/{id}/payment-methods")
    public ResponseEntity<?> updatePaymentMethods(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth) && !id.equals(getPlayerId(auth))) return ResponseEntity.status(403).build();
        return playerRepository.findById(id).map(p -> {
            if (body.containsKey("bit"))          p.setBitEnabled(Boolean.TRUE.equals(body.get("bit")));
            if (body.containsKey("paybox"))       p.setPayboxEnabled(Boolean.TRUE.equals(body.get("paybox")));
            if (body.containsKey("kashcash"))     p.setKashcashEnabled(Boolean.TRUE.equals(body.get("kashcash")));
            if (body.containsKey("cash"))         p.setCashEnabled(Boolean.TRUE.equals(body.get("cash")));
            if (body.containsKey("bankTransfer")) p.setBankTransferEnabled(Boolean.TRUE.equals(body.get("bankTransfer")));
            return ResponseEntity.ok(playerRepository.save(p));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/agent")
    public ResponseEntity<?> setAgent(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        try {
            Player player = playerService.getPlayer(id);
            Object agentIdVal = body.get("agentId");
            if (agentIdVal == null || agentIdVal.toString().isBlank()) {
                player.setAgent(null);
            } else {
                Long agentId = Long.parseLong(agentIdVal.toString());
                Player agent = playerService.getPlayer(agentId);
                player.setAgent(agent);
            }
            return ResponseEntity.ok(playerRepository.save(player));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePlayer(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        try {
            playerService.deletePlayer(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    private boolean isAgentOfPlayer(Authentication auth, Long targetPlayerId) {
        Long agentPlayerId = getPlayerId(auth);
        return playerRepository.findById(targetPlayerId)
                .map(p -> agentPlayerId.equals(p.getAgentId()))
                .orElse(false);
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
