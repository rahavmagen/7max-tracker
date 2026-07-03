package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final PlayerRepository playerRepository;
    private final GameResultRepository gameResultRepository;
    private final AgentSettlementRepository agentSettlementRepository;
    private final AdminExpenseRepository adminExpenseRepository;

    /** All agents with their pending (unsettled) balance, plus games played and total club rake
     *  by their players over an optional date range (null = all time). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllAgentsSummary(LocalDate from, LocalDate to) {
        List<Player> allPlayers = playerRepository.findAll();
        return allPlayers.stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsAgent()))
            .map(agent -> {
                BigDecimal pending = getUnsettledResults(agent.getId()).stream()
                    .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                long playerCount = allPlayers.stream()
                    .filter(p -> p.getAgent() != null && agent.getId().equals(p.getAgent().getId()))
                    .filter(p -> !p.getId().equals(agent.getId()))
                    .count();

                // Games played and total club rake by this agent's players, over the date range.
                // Exclude the agent's OWN games — only their players count, same as the detail page.
                final Long agentId = agent.getId();
                List<GameResult> results = gameResultRepository.findAllByAgentId(agentId).stream()
                    .filter(gr -> !gr.getPlayer().getId().equals(agentId))
                    .filter(gr -> {
                        LocalDate d = gr.getSession().getStartTime().toLocalDate();
                        if (from != null && d.isBefore(from)) return false;
                        if (to != null && d.isAfter(to)) return false;
                        return true;
                    })
                    .collect(Collectors.toList());
                long gameCount = results.size();
                BigDecimal totalRake = results.stream()
                    .map(gr -> gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                // Active players: distinct players with >= 1 game in range (agent already excluded)
                long activePlayerCount = results.stream()
                    .map(gr -> gr.getPlayer().getId())
                    .distinct()
                    .count();

                List<AgentSettlement> settlements = agentSettlementRepository.findByAgentIdOrderByCreatedAtDesc(agent.getId());
                LocalDate lastSettlement = settlements.isEmpty() ? null : settlements.get(0).getToDate();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", agent.getId());
                m.put("username", agent.getUsername());
                m.put("fullName", agent.getFullName());
                m.put("rakePercentage", agent.getAgentRakePercentage());
                m.put("pendingBalance", pending);
                m.put("playerCount", playerCount);
                m.put("activePlayerCount", activePlayerCount);
                m.put("gameCount", gameCount);
                m.put("totalRake", totalRake);
                m.put("lastSettlementDate", lastSettlement != null ? lastSettlement.toString() : null);
                return m;
            })
            .collect(Collectors.toList());
    }

    /** Pending balance + settlement history for one agent */
    @Transactional(readOnly = true)
    public Map<String, Object> getAgentSummary(Long agentId) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getIsAgent())) {
            throw new IllegalArgumentException("Player " + agentId + " is not an agent");
        }

        List<GameResult> unsettled = getUnsettledResults(agentId);
        BigDecimal pending = unsettled.stream()
            .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AgentSettlement> settlements = agentSettlementRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
        List<Map<String, Object>> historyList = settlements.stream().map(s -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("id", s.getId());
            h.put("fromDate", s.getFromDate() != null ? s.getFromDate().toString() : null);
            h.put("toDate", s.getToDate() != null ? s.getToDate().toString() : null);
            h.put("totalRake", s.getTotalRake());
            h.put("agentShare", s.getAgentShare());
            h.put("status", "PAID");
            return h;
        }).collect(Collectors.toList());

        List<Map<String, Object>> playersList = playerRepository.findAll().stream()
            .filter(p -> p.getAgent() != null && agentId.equals(p.getAgent().getId()))
            .filter(p -> !p.getId().equals(agentId))
            .map(p -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", p.getId());
                pm.put("username", p.getUsername());
                pm.put("fullName", p.getFullName());
                return pm;
            })
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("username", agent.getUsername());
        result.put("rakePercentage", agent.getAgentRakePercentage());
        result.put("pendingBalance", pending);
        result.put("players", playersList);
        result.put("settlementHistory", historyList);
        return result;
    }

    /** Game-by-game breakdown of unsettled results for an agent, optional date filter */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAgentBreakdown(Long agentId, LocalDate from, LocalDate to) {
        return getUnsettledResults(agentId).stream()
            .filter(gr -> {
                LocalDate sessionDate = gr.getSession().getStartTime().toLocalDate();
                if (from != null && sessionDate.isBefore(from)) return false;
                if (to != null && sessionDate.isAfter(to)) return false;
                return true;
            })
            .map(gr -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("gameResultId", gr.getId());
                m.put("sessionDate", gr.getSession().getStartTime().toLocalDate().toString());
                m.put("tableName", gr.getSession().getTableName());
                m.put("playerUsername", gr.getPlayer().getUsername());
                m.put("rakePaid", gr.getRakePaid());
                m.put("agentShare", gr.getAgentRakeShare());
                m.put("status", "pending");
                return m;
            })
            .collect(Collectors.toList());
    }

    /** Create a settlement: mark all unsettled results, create AgentSettlement + AdminExpense */
    @Transactional
    public AgentSettlement settleAgent(Long agentId) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getIsAgent())) {
            throw new IllegalArgumentException("Player " + agentId + " is not an agent");
        }

        List<GameResult> unsettled = getUnsettledResults(agentId);
        if (unsettled.isEmpty()) throw new IllegalStateException("No pending balance to settle");

        BigDecimal totalRake = unsettled.stream()
            .map(gr -> gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal agentShare = unsettled.stream()
            .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate fromDate = unsettled.stream()
            .map(gr -> gr.getSession().getStartTime().toLocalDate())
            .min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate toDate = unsettled.stream()
            .map(gr -> gr.getSession().getStartTime().toLocalDate())
            .max(LocalDate::compareTo).orElse(LocalDate.now());

        // Create AdminExpense of type AGENT
        AdminExpense expense = new AdminExpense();
        expense.setAdminUsername(agent.getUsername());
        expense.setAmount(agentShare);
        expense.setNotes("Agent fee: " + fromDate + " \u2013 " + toDate);
        expense.setExpenseDate(LocalDate.now());
        expense.setCreatedBy("system");
        expense.setExpenseType("AGENT");
        expense = adminExpenseRepository.save(expense);

        // Create AgentSettlement
        AgentSettlement settlement = new AgentSettlement();
        settlement.setAgent(agent);
        settlement.setFromDate(fromDate);
        settlement.setToDate(toDate);
        settlement.setTotalRake(totalRake);
        settlement.setAgentShare(agentShare);
        settlement.setAdminExpense(expense);
        settlement = agentSettlementRepository.save(settlement);

        // Mark all game results as settled
        final AgentSettlement finalSettlement = settlement;
        unsettled.forEach(gr -> gr.setAgentSettlement(finalSettlement));
        gameResultRepository.saveAll(unsettled);

        return settlement;
    }

    private static final Set<GameSession.GameType> TOURNAMENT_TYPES = Set.of(
        GameSession.GameType.MTT, GameSession.GameType.SNG, GameSession.GameType.AoF, GameSession.GameType.SPIN_GOLD
    );

    /** resultAmount, tournament-adjusted (resultAmount - buyIn) for MTT/SNG/AoF/SPIN_GOLD */
    private static BigDecimal pnlOf(GameResult gr) {
        BigDecimal resultAmount = gr.getResultAmount() != null ? gr.getResultAmount() : BigDecimal.ZERO;
        if (TOURNAMENT_TYPES.contains(gr.getSession().getGameType())) {
            BigDecimal buyIn = gr.getBuyIn() != null ? gr.getBuyIn() : BigDecimal.ZERO;
            return resultAmount.subtract(buyIn);
        }
        return resultAmount;
    }

    /** Per-player rake stats for an agent, with optional date filter (all results, settled+unsettled) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerStats(Long agentId, LocalDate from, LocalDate to) {
        playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        // All players under this agent (excluding the agent themselves)
        List<Player> agentPlayers = playerRepository.findAll().stream()
            .filter(p -> p.getAgent() != null && agentId.equals(p.getAgent().getId()))
            .filter(p -> !p.getId().equals(agentId))
            .collect(Collectors.toList());

        // Game results grouped by player id
        Map<Long, List<GameResult>> resultsByPlayer = gameResultRepository.findAllByAgentId(agentId).stream()
            .filter(gr -> {
                LocalDate d = gr.getSession().getStartTime().toLocalDate();
                if (from != null && d.isBefore(from)) return false;
                if (to != null && d.isAfter(to)) return false;
                return true;
            })
            .collect(Collectors.groupingBy(gr -> gr.getPlayer().getId()));

        return agentPlayers.stream()
            .map(player -> {
                List<GameResult> rows = resultsByPlayer.getOrDefault(player.getId(), Collections.emptyList());
                BigDecimal totalRake = rows.stream()
                    .map(gr -> gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal agentShare = rows.stream()
                    .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal periodPnl = rows.stream()
                    .map(AgentService::pnlOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                List<Map<String, Object>> games = rows.stream()
                    .sorted((a, b) -> b.getSession().getStartTime().compareTo(a.getSession().getStartTime()))
                    .map(gr -> {
                        Map<String, Object> g = new LinkedHashMap<>();
                        g.put("date", gr.getSession().getStartTime().toString());
                        g.put("tableName", gr.getSession().getTableName());
                        g.put("gameType", gr.getSession().getGameType().name());
                        g.put("pnl", pnlOf(gr));
                        g.put("buyIn", gr.getBuyIn());
                        g.put("cashout", gr.getCashout());
                        g.put("rakePaid", gr.getRakePaid());
                        return g;
                    })
                    .collect(Collectors.toList());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("playerId", player.getId());
                m.put("username", player.getUsername());
                m.put("fullName", player.getFullName());
                m.put("balance", player.getBalance());
                m.put("gameCount", rows.size());
                m.put("totalRake", totalRake);
                m.put("agentShare", agentShare);
                m.put("periodPnl", periodPnl);
                m.put("games", games);
                return m;
            })
            .sorted((a, b) -> ((BigDecimal) b.get("agentShare")).compareTo((BigDecimal) a.get("agentShare")))
            .collect(Collectors.toList());
    }

    @Transactional
    public void setRakePercentage(Long agentId, BigDecimal percentage) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getIsAgent()))
            throw new IllegalArgumentException("Player " + agentId + " is not an agent");
        agent.setAgentRakePercentage(percentage);
        playerRepository.save(agent);
    }

    private List<GameResult> getUnsettledResults(Long agentId) {
        return gameResultRepository.findUnsettledByAgentId(agentId);
    }
}
