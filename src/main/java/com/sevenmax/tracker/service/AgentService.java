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

    /** All agents with their pending (unsettled) balance */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllAgentsSummary() {
        List<Player> allPlayers = playerRepository.findAll();
        return allPlayers.stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsAgent()))
            .map(agent -> {
                BigDecimal pending = getUnsettledResults(agent.getId()).stream()
                    .map(gr -> gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                long playerCount = allPlayers.stream()
                    .filter(p -> p.getAgent() != null && agent.getId().equals(p.getAgent().getId()))
                    .count();
                List<AgentSettlement> settlements = agentSettlementRepository.findByAgentIdOrderByCreatedAtDesc(agent.getId());
                LocalDate lastSettlement = settlements.isEmpty() ? null : settlements.get(0).getToDate();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", agent.getId());
                m.put("username", agent.getUsername());
                m.put("fullName", agent.getFullName());
                m.put("pendingBalance", pending);
                m.put("playerCount", playerCount);
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("username", agent.getUsername());
        result.put("pendingBalance", pending);
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

    private List<GameResult> getUnsettledResults(Long agentId) {
        return gameResultRepository.findUnsettledByAgentId(agentId);
    }
}
