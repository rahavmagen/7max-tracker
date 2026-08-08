package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.GameResult;
import com.sevenmax.tracker.entity.GameSession;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.GameSessionRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Satellite-backing P&L (read-only report). For a {@code satelliteBacked} player the club fronts the
 * satellite buy-ins, absorbs losses, and splits net profit 50/50.
 *
 * <p>A session is treated as a satellite when its <b>first-place prize</b> (max resultAmount across
 * its players) equals the base entry cost of another <b>same-day</b> MTT — that matched MTT is the
 * target the ticket feeds. This is the same idea as the wheel-expense matching, applied to winnings
 * instead of a transfer, and it deliberately excludes live-event satellites (a live seat won't match
 * any same-day online tournament's cost). Name matching is intentionally NOT used.
 *
 * <p>Cost = all the player's sat buy-ins (real per-entry cost from entry_fee, so ₪500 = 2×₪250).
 * Return = a target's gross winnings only when the player did NOT re-enter it with his own money
 * (target entries ≤ sat tickets won for it); any own-money re-entry makes the win entirely his.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SatelliteBackingService {

    private final GameResultRepository gameResultRepository;
    private final GameSessionRepository gameSessionRepository;
    private final PlayerRepository playerRepository;

    /** Report rows for every satellite-backed player over [from,to] (clipped to satelliteBackedSince). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> report(LocalDate from, LocalDate to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Player p : playerRepository.findAll()) {
            if (!Boolean.TRUE.equals(p.getSatelliteBacked())) continue;
            Map<String, Object> row = reportForPlayer(p, from, to);
            if (row != null) out.add(row);
        }
        return out;
    }

    private Map<String, Object> reportForPlayer(Player player, LocalDate from, LocalDate to) {
        LocalDate effectiveFrom = from;
        if (player.getSatelliteBackedSince() != null && player.getSatelliteBackedSince().isAfter(from)) {
            effectiveFrom = player.getSatelliteBackedSince();
        }
        if (effectiveFrom.isAfter(to)) return null;

        // The player's own MTT results in range, indexed by session and grouped by day.
        Map<LocalDate, List<GameResult>> byDay = new LinkedHashMap<>();
        for (GameResult gr : gameResultRepository.findByPlayerIdOrderBySessionStartTimeDesc(player.getId())) {
            GameSession s = gr.getSession();
            if (s == null || s.getGameType() != GameSession.GameType.MTT || s.getStartTime() == null) continue;
            LocalDate d = s.getStartTime().toLocalDate();
            if (d.isBefore(effectiveFrom) || d.isAfter(to)) continue;
            byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(gr);
        }

        BigDecimal satCost = BigDecimal.ZERO;          // ALL sat buy-ins the club fronted
        BigDecimal sharedReturns = BigDecimal.ZERO;    // target winnings on sat-funded (shared) events
        BigDecimal playerShare = BigDecimal.ZERO;      // Σ per-event playerKeep = (win − winning-sat cost)/2
        BigDecimal clubCollected = BigDecimal.ZERO;    // Σ (win − playerKeep) = club's take from the win pots
        List<Map<String, Object>> breakdown = new ArrayList<>();

        for (Map.Entry<LocalDate, List<GameResult>> e : byDay.entrySet()) {
            LocalDate day = e.getKey();
            List<GameResult> playerDayResults = e.getValue();

            // All MTT sessions that day, with their base cost + first-place prize precomputed once.
            List<GameSession> dayMtts = gameSessionRepository.findByGameTypeAndStartTimeBetween(
                    GameSession.GameType.MTT, day.atTime(0, 0), day.atTime(23, 59, 59));
            Map<Long, BigDecimal> baseCostBySession = new LinkedHashMap<>();
            Map<Long, BigDecimal> firstPrizeBySession = new LinkedHashMap<>();
            for (GameSession s : dayMtts) {
                List<GameResult> rs = gameResultRepository.findBySessionId(s.getId());
                baseCostBySession.put(s.getId(), baseCost(s, rs));
                firstPrizeBySession.put(s.getId(), firstPlacePrize(rs));
            }
            // Base-cost -> session lookup, so we can find the target of a given ticket amount.
            Map<String, GameSession> byBaseCost = new LinkedHashMap<>();
            for (GameSession s : dayMtts) {
                BigDecimal bc = baseCostBySession.get(s.getId());
                if (bc != null && bc.signum() > 0) byBaseCost.putIfAbsent(bc.stripTrailingZeros().toPlainString(), s);
            }

            List<Map<String, Object>> satDetails = new ArrayList<>();
            // targetSessionId -> tickets the player won toward it that day
            Map<Long, Integer> ticketsByTarget = new LinkedHashMap<>();
            Map<Long, GameSession> targetSessions = new LinkedHashMap<>();
            // targetSessionId -> buy-in of the winning sat(s) that produced its ticket(s) (the only
            // cost reduced from a shared win; every other sat is a pure club loss).
            Map<Long, BigDecimal> winSatCostByTarget = new LinkedHashMap<>();

            for (GameResult gr : playerDayResults) {
                GameSession s = gr.getSession();
                BigDecimal prize = firstPrizeBySession.getOrDefault(s.getId(), BigDecimal.ZERO);
                GameSession target = prize.signum() > 0 ? byBaseCost.get(prize.stripTrailingZeros().toPlainString()) : null;
                boolean isSatellite = target != null && !target.getId().equals(s.getId());
                if (!isSatellite) continue;

                BigDecimal buyIn = nz(gr.getBuyIn());
                satCost = satCost.add(buyIn);

                BigDecimal satBase = baseCostBySession.getOrDefault(s.getId(), BigDecimal.ZERO);
                BigDecimal targetBase = baseCostBySession.getOrDefault(target.getId(), BigDecimal.ZERO);
                int entries = entries(buyIn, satBase);
                BigDecimal won = nz(gr.getResultAmount());

                Map<String, Object> sd = new LinkedHashMap<>();
                sd.put("name", s.getTableName());
                sd.put("entries", entries);
                sd.put("cost", buyIn);
                sd.put("wonAmount", won);
                sd.put("targetMatched", target.getTableName());
                satDetails.add(sd);

                // A won sat (player's own result > 0) grants tickets toward its target, and its
                // buy-in is the cost that will be reduced from that target's win if it cashes.
                if (won.signum() > 0 && targetBase.signum() > 0) {
                    int tickets = entries(won, targetBase);
                    if (tickets < 1) tickets = 1;
                    ticketsByTarget.merge(target.getId(), tickets, Integer::sum);
                    targetSessions.putIfAbsent(target.getId(), target);
                    winSatCostByTarget.merge(target.getId(), buyIn, BigDecimal::add);
                }
            }

            List<Map<String, Object>> targetDetails = new ArrayList<>();
            for (Map.Entry<Long, Integer> te : ticketsByTarget.entrySet()) {
                Long targetId = te.getKey();
                int tickets = te.getValue();
                GameSession target = targetSessions.get(targetId);
                GameResult playerInTarget = playerDayResults.stream()
                        .filter(g -> g.getSession() != null && g.getSession().getId().equals(targetId))
                        .findFirst().orElse(null);

                Map<String, Object> td = new LinkedHashMap<>();
                td.put("name", target.getTableName());
                td.put("tickets", tickets);
                if (playerInTarget == null) {
                    // Won a ticket but never played the target -> club just ate the sat cost.
                    td.put("entries", 0);
                    td.put("result", BigDecimal.ZERO);
                    td.put("counted", "unused");
                } else {
                    BigDecimal targetBase = baseCostBySession.getOrDefault(targetId, BigDecimal.ZERO);
                    int entries = entries(nz(playerInTarget.getBuyIn()), targetBase);
                    BigDecimal result = nz(playerInTarget.getResultAmount());
                    td.put("entries", entries);
                    td.put("result", result);
                    if (entries > tickets) {
                        td.put("counted", "own");            // own-money re-entry -> his win, not shared
                    } else if (result.signum() > 0) {
                        // Shared win: reduce ONLY the winning sat's cost, split the rest 50/50.
                        // Player keeps profit/2; the club takes the rest of the pot (its cost back +
                        // its profit half). Every other sat that day/month stays a full club loss.
                        BigDecimal feedCost = winSatCostByTarget.getOrDefault(targetId, BigDecimal.ZERO);
                        BigDecimal profit = result.subtract(feedCost);
                        BigDecimal playerKeep = profit.signum() > 0
                                ? profit.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                        sharedReturns = sharedReturns.add(result);
                        playerShare = playerShare.add(playerKeep);
                        clubCollected = clubCollected.add(result.subtract(playerKeep));
                        td.put("feedCost", feedCost);
                        td.put("profit", profit);
                        td.put("playerKeep", playerKeep);
                        td.put("counted", "shared");
                    } else {
                        td.put("counted", "busted");
                    }
                }
                targetDetails.add(td);
            }

            if (!satDetails.isEmpty()) {
                Map<String, Object> dayMap = new LinkedHashMap<>();
                dayMap.put("date", day.toString());
                dayMap.put("sats", satDetails);
                dayMap.put("targets", targetDetails);
                breakdown.add(dayMap);
            }
        }

        // Per-event model: a sat win is only a ticket. Each cashed sat-funded target is split after
        // reducing ONLY its winning sat's cost (player keeps half the profit; club takes the rest of
        // the pot). Every other sat is a full club loss. So:
        //   playerShare = Σ (win − winning-sat cost)/2   (player never bears sat costs)
        //   clubGets    = Σ (win − playerKeep)           (club's take from the winning pots)
        //   clubNet     = clubGets − ALL sat costs       (club eats every losing sat)
        BigDecimal clubGets = clubCollected;
        BigDecimal clubNet = clubCollected.subtract(satCost);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("playerId", player.getId());
        row.put("username", player.getUsername());
        row.put("satelliteBackedSince", player.getSatelliteBackedSince() != null ? player.getSatelliteBackedSince().toString() : null);
        row.put("satCost", satCost);
        row.put("sharedReturns", sharedReturns);
        row.put("playerShare", playerShare);
        row.put("clubGets", clubGets);
        row.put("clubNet", clubNet);
        row.put("breakdown", breakdown);
        return row;
    }

    /** Base per-entry cost of a session: entry_fee if set, else the minimum non-zero buy-in. */
    private BigDecimal baseCost(GameSession s, List<GameResult> results) {
        if (s.getEntryFee() != null && s.getEntryFee().signum() > 0) return s.getEntryFee();
        return results.stream()
                .map(GameResult::getBuyIn)
                .filter(b -> b != null && b.signum() > 0)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    /** First-place prize of a session = the biggest gross winnings (resultAmount) any player took. */
    private BigDecimal firstPlacePrize(List<GameResult> results) {
        return results.stream()
                .map(GameResult::getResultAmount)
                .filter(java.util.Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    /** How many entries a total buy-in represents at the given per-entry base cost. */
    private int entries(BigDecimal totalBuyIn, BigDecimal base) {
        if (base == null || base.signum() <= 0 || totalBuyIn == null || totalBuyIn.signum() <= 0) return 1;
        int n = totalBuyIn.divide(base, 0, RoundingMode.HALF_UP).intValue();
        return Math.max(1, n);
    }

    private BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
