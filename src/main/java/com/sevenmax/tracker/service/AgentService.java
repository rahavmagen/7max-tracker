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
    private final TransactionRepository transactionRepository;
    private final AgentLedgerEntryRepository agentLedgerEntryRepository;

    /** Free-chip credit for one agent player, with the transaction-history fallback.
     *  Base: freeCredit = currentChips − lifetime game P&L − already-booked credit.
     *  If that's negative (doesn't reconcile), the player likely paid real money for some chips
     *  (recorded as a PAYMENT/transfer); add those back before flagging. Only flag if still negative. */
    private Map<String, Object> computeFreeChipCredit(Player player) {
        BigDecimal chips = player.getCurrentChips() != null ? player.getCurrentChips() : BigDecimal.ZERO;
        BigDecimal existingCredit = player.getCreditTotal() != null ? player.getCreditTotal() : BigDecimal.ZERO;
        BigDecimal lifetimePnl = gameResultRepository.findByPlayerIdOrderBySessionStartTimeDesc(player.getId())
            .stream().map(AgentService::pnlOf).reduce(BigDecimal.ZERO, BigDecimal::add);

        // PRIMARY credit = derived: chips − game P&L − already-booked credit (with paid-buy-in fallback).
        BigDecimal freeCredit = chips.subtract(lifetimePnl).subtract(existingCredit);
        boolean reconciles = freeCredit.compareTo(BigDecimal.ZERO) >= 0;
        BigDecimal paidOut = BigDecimal.ZERO;
        if (!reconciles) {
            paidOut = transactionRepository.findByPlayerIdOrderByTransactionDateDesc(player.getId()).stream()
                .filter(t -> t.getType() == Transaction.Type.PAYMENT)
                .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal adjusted = freeCredit.add(paidOut);
            if (adjusted.compareTo(BigDecimal.ZERO) >= 0) { freeCredit = adjusted; reconciles = true; }
        }

        // CROSS-CHECK against the independently-caught pool-based grant (Player.agentChipCredit).
        // If the two methods disagree beyond tolerance, flag for review rather than trust either.
        BigDecimal crossCheck = player.getAgentChipCredit();
        if (crossCheck != null) {
            BigDecimal diff = freeCredit.subtract(crossCheck).abs();
            if (diff.compareTo(CROSSCHECK_TOLERANCE) > 0) reconciles = false;
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("currentChips", chips);
        r.put("lifetimePnl", lifetimePnl);
        r.put("existingCredit", existingCredit);
        r.put("paidAdjustment", paidOut);
        r.put("agentChipCredit", freeCredit);
        r.put("crossCheck", crossCheck);
        r.put("reconciles", reconciles);
        return r;
    }

    /** How far the derived and caught credit may differ before a player is flagged for review. */
    private static final BigDecimal CROSSCHECK_TOLERANCE = new BigDecimal("100");

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
                // Players' net P&L over the date range (won = positive), same pnlOf used everywhere.
                BigDecimal periodPnl = results.stream()
                    .map(AgentService::pnlOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                // Active players: distinct players with >= 1 game in range (agent already excluded)
                long activePlayerCount = results.stream()
                    .map(gr -> gr.getPlayer().getId())
                    .distinct()
                    .count();

                // Free-chip credit total (READ-ONLY, lifetime, NOT date-filtered): sum over this
                // agent's players of (currentChips − lifetime game P&L) = the free chips they hold.
                // Free-chip credit per player (with the transaction fallback), summed for this agent.
                // Also collect any players that STILL don't reconcile — surfaced on the main screen.
                List<Player> agentPlayers = allPlayers.stream()
                    .filter(p -> p.getAgent() != null && agentId.equals(p.getAgent().getId()))
                    .filter(p -> !p.getId().equals(agentId))
                    .collect(Collectors.toList());
                boolean clubManaged = Boolean.TRUE.equals(agent.getClubManaged());
                BigDecimal freeCreditTotal = BigDecimal.ZERO;
                List<Map<String, Object>> flaggedPlayers = new ArrayList<>();
                for (Player p : agentPlayers) {
                    Map<String, Object> info = computeFreeChipCredit(p);
                    freeCreditTotal = freeCreditTotal.add((BigDecimal) info.get("agentChipCredit"));
                    boolean reviewed = Boolean.TRUE.equals(p.getCreditReviewed());
                    if (!clubManaged && !reviewed && !Boolean.TRUE.equals(info.get("reconciles"))) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("id", p.getId());
                        f.put("username", p.getUsername());
                        flaggedPlayers.add(f);
                    }
                }

                List<AgentSettlement> settlements = agentSettlementRepository.findByAgentIdOrderByCreatedAtDesc(agent.getId());
                LocalDate lastSettlement = settlements.isEmpty() ? null : settlements.get(0).getToDate();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", agent.getId());
                m.put("username", agent.getUsername());
                m.put("fullName", agent.getFullName());
                m.put("rakePercentage", agent.getAgentRakePercentage());
                m.put("clubManaged", Boolean.TRUE.equals(agent.getClubManaged()));
                // Balance reconciles with the shown columns: starting − agentRake − P&L + payments, over [from,to].
                BigDecimal rakePct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;
                BigDecimal agentRake = rakePct.multiply(totalRake).setScale(2, java.math.RoundingMode.HALF_UP);
                AgentLedgerEntry openingE = agentLedgerEntryRepository
                    .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.OPENING).stream()
                    .max(Comparator.comparing(AgentLedgerEntry::getEffectiveDate).thenComparing(AgentLedgerEntry::getId))
                    .orElse(null);
                BigDecimal startBal = openingE != null ? openingE.getAmount() : BigDecimal.ZERO;
                BigDecimal pmts = agentLedgerEntryRepository
                    .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.PAYMENT).stream()
                    .filter(e -> inRange(e.getEffectiveDate(), from, to))
                    .map(AgentLedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal currentBal = startBal.add(agentRake).add(periodPnl).subtract(pmts);

                m.put("pendingBalance", pending);
                m.put("periodPnl", periodPnl);
                m.put("agentRake", agentRake);
                m.put("openingBalance", startBal);
                m.put("payments", pmts);
                m.put("currentBalance", currentBal);
                m.put("playerCount", playerCount);
                m.put("activePlayerCount", activePlayerCount);
                m.put("gameCount", gameCount);
                m.put("totalRake", totalRake);
                m.put("freeCreditTotal", freeCreditTotal);
                m.put("flaggedPlayers", flaggedPlayers);
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
                // Free-chip credit (READ-ONLY — not yet booked), with the transaction-history fallback.
                m.putAll(computeFreeChipCredit(player));
                m.put("games", games);
                return m;
            })
            .sorted((a, b) -> ((BigDecimal) b.get("agentShare")).compareTo((BigDecimal) a.get("agentShare")))
            .collect(Collectors.toList());
    }

    /**
     * Balance with an agent over a date range, from the AGENT's point of view
     * (positive = WE OWE THE AGENT; negative = the agent owes us):
     *   currentBalance = startingBalance + agentRake + players' P&L − payments   (accrual over [from, to]).
     * agentRake = the agent's rake% × total rake their players generated (rakeback we owe → +). Player winnings
     * mean we owe the agent (+); losses mean they owe us (−). A payment where WE pay the agent (+) reduces what
     * we owe (−payments). Columns reconcile: Total Rake → Agent Rake → P&L → Starting → Current Balance.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAgentBalance(Long agentId, LocalDate from, LocalDate to) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        BigDecimal rakePct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;

        // Starting balance = latest OPENING entry (the carry from the last התחשבנות).
        AgentLedgerEntry baseline = agentLedgerEntryRepository
            .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.OPENING).stream()
            .max(Comparator.comparing(AgentLedgerEntry::getEffectiveDate).thenComparing(AgentLedgerEntry::getId))
            .orElse(null);
        LocalDate openingDate = baseline != null ? baseline.getEffectiveDate() : null;
        BigDecimal startingBalance = baseline != null ? baseline.getAmount() : BigDecimal.ZERO;

        // When no explicit from is given (e.g. the agent portal), accrue since the last התחשבנות so games
        // already captured in the starting balance are not double-counted.
        final LocalDate accrualFrom = from != null ? from : lastSettlementDate();

        List<GameResult> results = gameResultRepository.findAllByAgentId(agentId).stream()
            .filter(gr -> !gr.getPlayer().getId().equals(agentId))
            .filter(gr -> inRange(gr.getSession().getStartTime().toLocalDate(), accrualFrom, to))
            .collect(Collectors.toList());
        BigDecimal totalRake = results.stream()
            .map(gr -> gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal agentRake = rakePct.multiply(totalRake).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal playerPnl = results.stream().map(AgentService::pnlOf).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal payments = agentLedgerEntryRepository
            .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.PAYMENT).stream()
            .filter(e -> inRange(e.getEffectiveDate(), accrualFrom, to))
            .map(AgentLedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Agent point of view (positive = WE OWE THE AGENT; negative = agent owes us):
        // starting + agentRake (we owe them) + players' P&L (won → we owe) − payments (we paid them).
        BigDecimal currentBalance = startingBalance.add(agentRake).add(playerPnl).subtract(payments);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", agentId);
        m.put("hasBaseline", baseline != null);
        m.put("openingDate", openingDate != null ? openingDate.toString() : null);
        m.put("openingBalance", startingBalance);
        m.put("totalRake", totalRake);
        m.put("rakebackSince", agentRake);      // agent's rake cut for the range (kept key for the UI)
        m.put("playerPnlSince", playerPnl);
        m.put("paymentsSince", payments);
        m.put("currentBalance", currentBalance);
        return m;
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        if (d == null) return false;
        if (from != null && d.isBefore(from)) return false;
        if (to != null && d.isAfter(to)) return false;
        return true;
    }

    @Transactional
    public AgentLedgerEntry addLedgerEntry(Long agentId, AgentLedgerEntry.Type type, BigDecimal amount,
                                           LocalDate effectiveDate, String notes, String user) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getIsAgent()))
            throw new IllegalArgumentException("Player " + agentId + " is not an agent");
        if (amount == null) throw new IllegalArgumentException("amount is required");
        AgentLedgerEntry e = new AgentLedgerEntry();
        e.setAgent(agent);
        e.setType(type);
        e.setAmount(amount);
        e.setEffectiveDate(effectiveDate != null ? effectiveDate : LocalDate.now());
        e.setNotes(notes);
        e.setCreatedBy(user);
        return agentLedgerEntryRepository.save(e);
    }

    @Transactional(readOnly = true)
    public List<AgentLedgerEntry> getLedger(Long agentId) {
        return agentLedgerEntryRepository.findByAgentIdOrderByEffectiveDateDescIdDesc(agentId);
    }

    /**
     * Total balance across all (non-club-managed) agents for a period — matches the agents page total.
     * from defaults to the last התחשבנות date. Positive = we owe agents (net); negative = agents owe us (net).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getTotalAgentBalance(LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : lastSettlementDate();
        BigDecimal total = playerRepository.findAll().stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsAgent()) && !Boolean.TRUE.equals(p.getClubManaged()))
            .map(a -> (BigDecimal) getAgentBalance(a.getId(), f, to).get("currentBalance"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", f != null ? f.toString() : null);
        m.put("to", to != null ? to.toString() : null);
        m.put("totalBalance", total);
        return m;
    }

    /** Full transaction history across all agents (openings + payments), newest first, with agent name. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLedgerHistory() {
        Map<Long, String> names = playerRepository.findAll().stream()
            .collect(Collectors.toMap(Player::getId, Player::getUsername, (a, b) -> a));
        return agentLedgerEntryRepository.findAll().stream()
            .sorted(Comparator.comparing(AgentLedgerEntry::getEffectiveDate)
                .thenComparing(AgentLedgerEntry::getId).reversed())
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("agentId", e.getAgentId());
                m.put("agent", names.getOrDefault(e.getAgentId(), "#" + e.getAgentId()));
                m.put("type", e.getType().name());
                m.put("effectiveDate", e.getEffectiveDate() != null ? e.getEffectiveDate().toString() : null);
                m.put("amount", e.getAmount());
                m.put("notes", e.getNotes());
                m.put("createdBy", e.getCreatedBy());
                m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
                return m;
            })
            .collect(Collectors.toList());
    }

    @Transactional
    public void deleteLedgerEntry(Long entryId) {
        agentLedgerEntryRepository.deleteById(entryId);
    }

    /**
     * The most recent התחשבנות (settlement) date: take each agent's latest PAYMENT date, then return the
     * date shared by the most agents (tie → the more recent). התחשבנות is usually done for several agents
     * on the same day, so that shared date marks the start of the current open period. null if no payments.
     */
    @Transactional(readOnly = true)
    public LocalDate lastSettlementDate() {
        Map<Long, LocalDate> latestPerAgent = new HashMap<>();
        // Ledger entries: a PAYMENT or a starting-balance OPENING both mark a התחשבנות checkpoint.
        for (AgentLedgerEntry e : agentLedgerEntryRepository.findAll()) {
            if (e.getAgentId() == null || e.getEffectiveDate() == null) continue;
            if (e.getType() != AgentLedgerEntry.Type.PAYMENT && e.getType() != AgentLedgerEntry.Type.OPENING) continue;
            latestPerAgent.merge(e.getAgentId(), e.getEffectiveDate(), (a, b) -> b.isAfter(a) ? b : a);
        }
        // Legacy settlements (so the default works during the transition, before Settle & Pay is used).
        for (AgentSettlement s : agentSettlementRepository.findAll()) {
            if (s.getAgent() == null || s.getToDate() == null) continue;
            latestPerAgent.merge(s.getAgent().getId(), s.getToDate(), (a, b) -> b.isAfter(a) ? b : a);
        }
        if (latestPerAgent.isEmpty()) return null;
        return latestPerAgent.values().stream()
            .collect(Collectors.groupingBy(d -> d, Collectors.counting()))
            .entrySet().stream()
            .max(Comparator.<Map.Entry<LocalDate, Long>>comparingLong(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey).orElse(null);
    }

    /** Admin acknowledged these players' reconciliation flags — drop them from the flagged list. */
    @Transactional
    public int dismissFlags(List<Long> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) return 0;
        List<Player> players = playerRepository.findAllById(playerIds);
        players.forEach(p -> p.setCreditReviewed(true));
        playerRepository.saveAll(players);
        return players.size();
    }

    @Transactional
    public void setClubManaged(Long agentId, boolean clubManaged) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getIsAgent()))
            throw new IllegalArgumentException("Player " + agentId + " is not an agent");
        agent.setClubManaged(clubManaged);
        playerRepository.save(agent);
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
