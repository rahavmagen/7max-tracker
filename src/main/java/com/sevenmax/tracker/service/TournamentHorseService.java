package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tournament Horses: the club stakes a player across an admin-chosen set of game types. Losses
 * are fronted via HorseTransaction entries (a running deficit, like a write-off ledger); the
 * player owes nothing while their winnings haven't yet covered that deficit. Once their
 * cumulative winnings (since joining) exceed the cumulative amount fronted, the surplus splits
 * 50/50 between player and club.
 */
@Service
@RequiredArgsConstructor
public class TournamentHorseService {

    private final PlayerRepository playerRepository;
    private final GameResultRepository gameResultRepository;
    private final HorseTransactionRepository horseTransactionRepository;

    private static final Set<GameSession.GameType> TOURNAMENT_TYPES = Set.of(
        GameSession.GameType.MTT, GameSession.GameType.SNG, GameSession.GameType.AoF, GameSession.GameType.SPIN_GOLD
    );

    /** resultAmount, tournament-adjusted (resultAmount - buyIn) for MTT/SNG/AoF/SPIN_GOLD, plain resultAmount otherwise. */
    private static BigDecimal pnlOf(GameResult gr) {
        BigDecimal resultAmount = gr.getResultAmount() != null ? gr.getResultAmount() : BigDecimal.ZERO;
        if (TOURNAMENT_TYPES.contains(gr.getSession().getGameType())) {
            BigDecimal buyIn = gr.getBuyIn() != null ? gr.getBuyIn() : BigDecimal.ZERO;
            return resultAmount.subtract(buyIn);
        }
        return resultAmount;
    }

    @Transactional
    public HorseTransaction addTransaction(Long playerId, BigDecimal amount, String notes, LocalDate date, String createdBy) {
        Player p = playerRepository.findById(playerId)
            .orElseThrow(() -> new IllegalArgumentException("Player not found: " + playerId));
        HorseTransaction t = new HorseTransaction();
        t.setPlayerId(p.getId());
        t.setAmount(amount);
        t.setNotes(notes);
        t.setTransactionDate(date != null ? date : LocalDate.now());
        t.setCreatedBy(createdBy);
        return horseTransactionRepository.save(t);
    }

    @Transactional(readOnly = true)
    public List<HorseTransaction> getTransactions(Long playerId) {
        return horseTransactionRepository.findByPlayerIdOrderByTransactionDateDesc(playerId);
    }

    /** Status for every Tournament Horse: winnings since joining, total fronted, net position,
     *  and (if net > 0) the player/club split. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> report() {
        List<Player> horses = playerRepository.findAll().stream()
            .filter(p -> Boolean.TRUE.equals(p.getTournamentHorseBacked()))
            .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Player horse : horses) {
            Set<String> gameTypes = horse.getTournamentHorseGameTypes() == null ? Set.of()
                : Arrays.stream(horse.getTournamentHorseGameTypes().split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
            LocalDate since = horse.getTournamentHorseBackedSince();
            LocalDate until = horse.getTournamentHorseBackedUntil();

            List<GameResult> countedGames = gameResultRepository.findByPlayerIdOrderBySessionStartTimeDesc(horse.getId()).stream()
                .filter(gr -> since == null || !gr.getSession().getStartTime().toLocalDate().isBefore(since))
                .filter(gr -> until == null || gr.getSession().getStartTime().toLocalDate().isBefore(until))
                .filter(gr -> gameTypes.contains(gr.getSession().getGameType().name()))
                .collect(Collectors.toList());
            BigDecimal winnings = countedGames.stream().map(TournamentHorseService::pnlOf).reduce(BigDecimal.ZERO, BigDecimal::add);

            List<HorseTransaction> transactions = horseTransactionRepository.findByPlayerIdOrderByTransactionDateDesc(horse.getId());
            BigDecimal fronted = transactions.stream()
                .map(HorseTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal net = winnings.subtract(fronted).setScale(2, RoundingMode.HALF_UP);
            boolean profitable = net.compareTo(BigDecimal.ZERO) > 0;
            BigDecimal playerShare = profitable ? net.divide(new BigDecimal(2), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal clubShare = profitable ? net.subtract(playerShare) : BigDecimal.ZERO;
            BigDecimal deficit = profitable ? BigDecimal.ZERO : net.negate();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("playerId", horse.getId());
            m.put("username", horse.getUsername());
            m.put("fullName", horse.getFullName());
            m.put("gameTypes", horse.getTournamentHorseGameTypes());
            m.put("backedSince", since != null ? since.toString() : null);
            m.put("backedUntil", until != null ? until.toString() : null);
            m.put("winnings", winnings.setScale(2, RoundingMode.HALF_UP));
            m.put("fronted", fronted.setScale(2, RoundingMode.HALF_UP));
            m.put("net", net);
            m.put("profitable", profitable);
            m.put("deficit", deficit);
            m.put("playerShare", playerShare);
            m.put("clubShare", clubShare);
            m.put("breakdown", buildBreakdown(countedGames, transactions));
            result.add(m);
        }
        return result;
    }

    /** Day-by-day breakdown: every counted game result and every horse transaction, newest day first. */
    private List<Map<String, Object>> buildBreakdown(List<GameResult> games, List<HorseTransaction> transactions) {
        Map<LocalDate, List<Map<String, Object>>> gamesByDay = new TreeMap<>(Comparator.reverseOrder());
        for (GameResult gr : games) {
            LocalDate day = gr.getSession().getStartTime().toLocalDate();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("gameType", gr.getSession().getGameType().name());
            g.put("tableName", gr.getSession().getTableName());
            g.put("amount", pnlOf(gr).setScale(2, RoundingMode.HALF_UP));
            gamesByDay.computeIfAbsent(day, d -> new ArrayList<>()).add(g);
        }
        Map<LocalDate, List<Map<String, Object>>> txByDay = new TreeMap<>(Comparator.reverseOrder());
        for (HorseTransaction t : transactions) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("amount", t.getAmount());
            tm.put("notes", t.getNotes());
            tm.put("createdBy", t.getCreatedBy());
            txByDay.computeIfAbsent(t.getTransactionDate(), d -> new ArrayList<>()).add(tm);
        }

        Set<LocalDate> allDays = new TreeSet<>(Comparator.reverseOrder());
        allDays.addAll(gamesByDay.keySet());
        allDays.addAll(txByDay.keySet());

        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (LocalDate day : allDays) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("games", gamesByDay.getOrDefault(day, List.of()));
            row.put("transactions", txByDay.getOrDefault(day, List.of()));
            breakdown.add(row);
        }
        return breakdown;
    }
}
