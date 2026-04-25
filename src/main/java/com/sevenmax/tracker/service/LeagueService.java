package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LeagueService {

    private final LeagueConfigRepository leagueConfigRepository;
    private final LeagueSessionConfigRepository leagueSessionConfigRepository;
    private final GameResultRepository gameResultRepository;

    public List<Map<String, Object>> computeStandings() {
        LeagueConfig config = leagueConfigRepository.findById(1L)
            .orElseGet(() -> { LeagueConfig c = new LeagueConfig(); c.setMinHands(100); return c; });
        int minHands = config.getMinHands();

        List<LeagueSessionConfig> includedSessions = leagueSessionConfigRepository.findByIncludedTrue();

        // Accumulate per player
        Map<Long, int[]>        handsMap      = new LinkedHashMap<>(); // [totalHands]
        Map<Long, long[]>       handsPtsMap   = new LinkedHashMap<>(); // [handsPoints]
        Map<Long, BigDecimal>   profitILSMap  = new LinkedHashMap<>();
        Map<Long, long[]>       profitPtsMap  = new LinkedHashMap<>(); // [profitPoints]
        Map<Long, long[]>       fixedPtsMap   = new LinkedHashMap<>(); // [fixedPoints]
        Map<Long, String>       usernameMap   = new LinkedHashMap<>();

        for (LeagueSessionConfig sc : includedSessions) {
            int hm = sc.getHandsMultiplier()  != null ? sc.getHandsMultiplier()  : 1;
            int pm = sc.getProfitMultiplier() != null ? sc.getProfitMultiplier() : 1;
            int fp = sc.getFixedPoints()      != null ? sc.getFixedPoints()      : 0;
            List<GameResult> results = gameResultRepository.findBySessionId(sc.getSession().getId());
            for (GameResult r : results) {
                Long pid = r.getPlayer().getId();
                usernameMap.put(pid, r.getPlayer().getUsername());

                int hands       = r.getHandsPlayed()  != null ? r.getHandsPlayed()  : 0;
                BigDecimal profit = r.getResultAmount() != null ? r.getResultAmount() : BigDecimal.ZERO;

                handsMap.computeIfAbsent(pid, k -> new int[]{0});
                handsMap.get(pid)[0] += hands;

                handsPtsMap.computeIfAbsent(pid, k -> new long[]{0});
                handsPtsMap.get(pid)[0] += (long) hands * hm;

                profitILSMap.merge(pid, profit, BigDecimal::add);

                profitPtsMap.computeIfAbsent(pid, k -> new long[]{0});
                profitPtsMap.get(pid)[0] += profit.multiply(BigDecimal.valueOf(pm)).longValue();

                fixedPtsMap.computeIfAbsent(pid, k -> new long[]{0});
                fixedPtsMap.get(pid)[0] += fp;
            }
        }

        // Build rows
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long pid : usernameMap.keySet()) {
            int        totalHands   = handsMap.getOrDefault(pid, new int[]{0})[0];
            long       handsPoints  = handsPtsMap.getOrDefault(pid, new long[]{0})[0];
            BigDecimal profitILS    = profitILSMap.getOrDefault(pid, BigDecimal.ZERO);
            long       profitPoints = profitPtsMap.getOrDefault(pid, new long[]{0})[0];
            long       fixedPoints  = fixedPtsMap.getOrDefault(pid, new long[]{0})[0];
            long       totalPoints  = handsPoints + profitPoints + fixedPoints;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerId",     pid);
            row.put("username",     usernameMap.get(pid));
            row.put("totalHands",   totalHands);
            row.put("handsPoints",  handsPoints);
            row.put("profitILS",    profitILS);
            row.put("profitPoints", profitPoints);
            row.put("fixedPoints",  fixedPoints);
            row.put("totalPoints",  totalPoints);
            row.put("qualified",    totalHands >= minHands);
            row.put("rank",         null);
            rows.add(row);
        }

        // Sort: qualified first by totalPoints desc, then unqualified by totalHands desc
        rows.sort((a, b) -> {
            boolean aq = (boolean) a.get("qualified");
            boolean bq = (boolean) b.get("qualified");
            if (aq && !bq) return -1;
            if (!aq && bq) return 1;
            if (aq) return Long.compare((long) b.get("totalPoints"), (long) a.get("totalPoints"));
            return Integer.compare((int) b.get("totalHands"), (int) a.get("totalHands"));
        });

        // Assign ranks to qualified players
        int rank = 1;
        for (Map<String, Object> row : rows) {
            if ((boolean) row.get("qualified")) {
                row.put("rank", rank++);
            }
        }

        return rows;
    }
}
