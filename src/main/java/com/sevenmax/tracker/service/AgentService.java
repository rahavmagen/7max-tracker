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
    private final LastSettlementDateRepository lastSettlementDateRepository;
    private final com.sevenmax.tracker.repository.LiveTicketRepository liveTicketRepository;

    /** Agent-rake expense management started on this date. Games before it are out of scope for
     *  settlement — their unsettled rake share is not owed and must not pre-fill / be marked settled. */
    private static final java.time.LocalDateTime EXPENSE_TRACKING_START =
            java.time.LocalDate.of(2026, 8, 1).atStartOfDay();

    /** Unused live-ticket worth owed via an agent (agent + their players) — a non-cash obligation. */
    private BigDecimal ticketWorthForAgent(Long agentId) {
        return liveTicketRepository.findByUsedFalseAndAgentId(agentId).stream()
                .map(t -> t.getWorth() != null ? t.getWorth() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Total sat buy-in cost of an agent's unused tickets. */
    private BigDecimal ticketCostForAgent(Long agentId) {
        return liveTicketRepository.findByUsedFalseAndAgentId(agentId).stream()
                .map(t -> t.getCost() != null ? t.getCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

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
    /** Every game result that counts toward an agent's page: their sub-players' games PLUS the
     *  agent's OWN play (deduped by result id). {@code findAllByAgentId} returns only sub-players
     *  (the agent isn't their own agent), so the agent's own results are merged in — the agent is
     *  treated like one of their own players (own P&L and own rake both count). */
    private List<GameResult> agentAndOwnResults(Long agentId) {
        java.util.LinkedHashMap<Long, GameResult> byId = new java.util.LinkedHashMap<>();
        // Roll up the whole subtree: a super agent absorbs every sub-agent's players and own play.
        // For a plain agent (no sub-agents) the subtree is just themselves, so this is a no-op.
        for (Long aid : subtreeAgentIds(agentId)) {
            for (GameResult gr : gameResultRepository.findAllByAgentId(aid)) byId.put(gr.getId(), gr);
            for (GameResult gr : gameResultRepository.findByPlayerIdOrderBySessionStartTimeDesc(aid)) byId.put(gr.getId(), gr);
        }
        return new ArrayList<>(byId.values());
    }

    // ── Super-agent hierarchy ────────────────────────────────────────────────
    // An agent can sit under a "super agent" (its own `agent` points at another agent). The club
    // settles directly with the super agent, so only super/top-level agents are shown and every
    // sub-agent's players + own play roll up into the super agent's book.

    /** The top-level (super) agent a player rolls up to: walk up while the parent is itself an agent. */
    private Player resolveTopAgent(Player p, Map<Long, Player> byId) {
        Player cur = p;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (cur != null && seen.add(cur.getId())) {
            Long parentId = cur.getAgentId();
            Player parent = parentId != null ? byId.get(parentId) : null;
            if (parent != null && Boolean.TRUE.equals(parent.getIsAgent())) cur = parent;
            else break;
        }
        return cur;
    }

    /** An agent shown on the list: it's an agent and is NOT under another agent. */
    private boolean isTopLevelAgent(Player p, Map<Long, Player> byId) {
        if (!Boolean.TRUE.equals(p.getIsAgent())) return false;
        Player top = resolveTopAgent(p, byId);
        return top != null && top.getId().equals(p.getId());
    }

    /** Whether a player belongs to a (super) agent's book: their top-level agent is this agent. */
    private boolean inAgentBook(Player p, Long topAgentId, Map<Long, Player> byId) {
        if (p.getId().equals(topAgentId)) return false;
        Player top = resolveTopAgent(p, byId);
        return top != null && topAgentId.equals(top.getId());
    }

    /** Agent IDs in a (super) agent's subtree: itself + every descendant sub-agent. */
    private List<Long> subtreeAgentIds(Long topAgentId) {
        List<Player> all = playerRepository.findAll();
        Map<Long, Player> byId = all.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        List<Long> ids = new ArrayList<>();
        for (Player p : all) {
            if (Boolean.TRUE.equals(p.getIsAgent())) {
                Player top = resolveTopAgent(p, byId);
                if (top != null && topAgentId.equals(top.getId())) ids.add(p.getId());
            }
        }
        if (!ids.contains(topAgentId)) ids.add(topAgentId);
        return ids;
    }

    /** The rake commission a (super) agent earns on one unsettled result. A player directly under
     *  this agent keeps their stored share (unchanged for existing agents); a player under a
     *  sub-agent is charged at the SUPER agent's % on the whole book. */
    private BigDecimal shareForTop(GameResult gr, Player topAgent, BigDecimal topPct) {
        Player pl = gr.getPlayer();
        if (pl != null && pl.getAgent() != null && topAgent.getId().equals(pl.getAgent().getId())) {
            return gr.getAgentRakeShare() != null ? gr.getAgentRakeShare() : BigDecimal.ZERO;
        }
        BigDecimal rake = gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO;
        return topPct.multiply(rake).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public List<Map<String, Object>> getAllAgentsSummary(LocalDate from, LocalDate to) {
        List<Player> allPlayers = playerRepository.findAll();
        Map<Long, Player> byId = allPlayers.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        return allPlayers.stream()
            .filter(p -> isTopLevelAgent(p, byId))
            .map(agent -> {
                BigDecimal agentPct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;
                BigDecimal pending = getUnsettledResults(agent.getId()).stream()
                    .map(gr -> shareForTop(gr, agent, agentPct))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                long playerCount = allPlayers.stream()
                    .filter(p -> inAgentBook(p, agent.getId(), byId))
                    .count();

                // Games played and total club rake by this agent's players AND the agent's own play,
                // over the date range (the agent is treated like one of their own players).
                final Long agentId = agent.getId();
                List<GameResult> results = agentAndOwnResults(agentId).stream()
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
                // Players' net P&L over the date range (won = positive). Sat-to-live games excluded.
                BigDecimal periodPnl = results.stream()
                    .map(AgentService::countedPnl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                // What the app (GG) shows: raw P&L including sat-to-live wins — for comparison.
                BigDecimal appPnl = results.stream()
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
                    .filter(p -> inAgentBook(p, agentId, byId))
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

                // Chips held by this agent + their players — mirrors TotalProfit.jsx's excludedChips
                // logic (agent's own chips + players' chips, skipping stale counts) so this figure
                // reconciles with Total Profit's "agent-held chips" line for non-club-managed agents.
                BigDecimal totalChips = agentPlayers.stream()
                    .filter(p -> !Boolean.TRUE.equals(p.getChipsStale()))
                    .map(p -> p.getCurrentChips() != null ? p.getCurrentChips() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (!Boolean.TRUE.equals(agent.getChipsStale())) {
                    totalChips = totalChips.add(agent.getCurrentChips() != null ? agent.getCurrentChips() : BigDecimal.ZERO);
                }

                List<AgentSettlement> settlements = agentSettlementRepository.findByAgentIdOrderByCreatedAtDesc(agent.getId());
                LocalDate lastSettlement = settlements.isEmpty() ? null : settlements.get(0).getToDate();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", agent.getId());
                m.put("username", agent.getUsername());
                m.put("fullName", agent.getFullName());
                m.put("phone", agent.getPhone());
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
                m.put("appPnl", appPnl);   // GG's view (sat-to-live counted) — for reconciliation
                m.put("agentRake", agentRake);
                m.put("openingBalance", startBal);
                m.put("openingDate", openingE != null && openingE.getEffectiveDate() != null ? openingE.getEffectiveDate().toString() : null);
                m.put("settledThisWeek", Boolean.TRUE.equals(agent.getAgentSettledThisWeek()));
                m.put("ticketWorth", ticketWorthForAgent(agentId));
                m.put("ticketCost", ticketCostForAgent(agentId));
                m.put("ticketProfit", ticketWorthForAgent(agentId).subtract(ticketCostForAgent(agentId)));
                m.put("payments", pmts);
                m.put("currentBalance", currentBal);
                m.put("playerCount", playerCount);
                m.put("activePlayerCount", activePlayerCount);
                m.put("gameCount", gameCount);
                m.put("totalRake", totalRake);
                m.put("totalChips", totalChips);
                m.put("freeCreditTotal", freeCreditTotal);
                m.put("flaggedPlayers", flaggedPlayers);
                m.put("lastSettlementDate", lastSettlement != null ? lastSettlement.toString() : null);
                return m;
            })
            .collect(Collectors.toList());
    }

    /** Mark/unmark that an agent's weekly התחשבנות was handled. */
    @Transactional
    public void setSettledThisWeek(Long agentId, boolean value) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        agent.setAgentSettledThisWeek(value);
        playerRepository.save(agent);
    }

    /** Clear the "settled this week" flag on every agent (the "uncheck all" button). */
    @Transactional
    public void clearAllSettledThisWeek() {
        List<Player> agents = playerRepository.findAll().stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsAgent()) && Boolean.TRUE.equals(p.getAgentSettledThisWeek()))
            .collect(Collectors.toList());
        for (Player a : agents) a.setAgentSettledThisWeek(false);
        playerRepository.saveAll(agents);
    }

    /** Pending balance + settlement history for one agent */
    @Transactional(readOnly = true)
    public Map<String, Object> getAgentSummary(Long agentId) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getIsAgent())) {
            throw new IllegalArgumentException("Player " + agentId + " is not an agent");
        }

        BigDecimal agentPct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;
        List<GameResult> unsettled = getUnsettledResults(agentId);
        BigDecimal pending = unsettled.stream()
            .map(gr -> shareForTop(gr, agent, agentPct))
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

        List<Player> allForBook = playerRepository.findAll();
        Map<Long, Player> bookById = allForBook.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        List<Map<String, Object>> playersList = allForBook.stream()
            .filter(p -> inAgentBook(p, agentId, bookById))
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

    /** Create a settlement: mark all unsettled results, create AgentSettlement + AdminExpense.
     *  overrideAmount, if provided, replaces the computed agentShare as the recorded expense -
     *  this is the figure an admin corrected in the settle popup, independent of how much of it
     *  actually got paid out in this particular transfer. */
    @Transactional
    public AgentSettlement settleAgent(Long agentId, BigDecimal overrideAmount) {
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
        BigDecimal agentPct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;
        BigDecimal computedAgentShare = unsettled.stream()
            .map(gr -> shareForTop(gr, agent, agentPct))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal agentShare = overrideAmount != null ? overrideAmount : computedAgentShare;
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

    /** Manually record an agent-rake Club Expense for an arbitrary amount, independent of any
     *  unsettled game results - for corrections/one-off amounts that don't come from settleAgent's
     *  per-game accrual. Does not touch GameResult/AgentSettlement, so it has no effect on what
     *  settleAgent later computes as pending. */
    @Transactional
    public AdminExpense addManualRakeExpense(Long agentId, BigDecimal amount, String notes, String createdBy) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getIsAgent())) {
            throw new IllegalArgumentException("Player " + agentId + " is not an agent");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        AdminExpense expense = new AdminExpense();
        expense.setAdminUsername(agent.getUsername());
        expense.setAmount(amount);
        expense.setNotes(notes != null && !notes.isBlank() ? notes : "Manual agent rake");
        expense.setExpenseDate(LocalDate.now());
        expense.setCreatedBy(createdBy != null ? createdBy : "system");
        expense.setExpenseType("AGENT");
        return adminExpenseRepository.save(expense);
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

    // "Satellite to a live event" (double-up): its P&L must not count toward the agent balance.
    // Effective = manual flag OR the tournament name contains a live/double-up marker.
    // "סאט לדאבל אפ" (sat to double-up) / "סאט ללייב". NOT bare "דאבל" — that also matches the
    // "דאבל בורד בומב פוט" cash game, whose real P&L must still count.
    private static final String[] SAT_TO_LIVE_MARKERS = { "לדאבל", "דאבל אפ", "לדבלאפ", "ללייב" };
    public static boolean isSatToLive(GameSession s) {
        if (s == null) return false;
        if (Boolean.TRUE.equals(s.getSatToLive())) return true;
        String n = s.getTableName();
        if (n == null) return false;
        for (String m : SAT_TO_LIVE_MARKERS) if (n.contains(m)) return true;
        return false;
    }

    /** P&L that counts toward the agent balance — zero for sat-to-live games (paid via live ticket). */
    private static BigDecimal countedPnl(GameResult gr) {
        return isSatToLive(gr.getSession()) ? BigDecimal.ZERO : pnlOf(gr);
    }

    /** Per-player rake stats for an agent, with optional date filter (all results, settled+unsettled) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerStats(Long agentId, LocalDate from, LocalDate to) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        // Everyone in this (super) agent's book PLUS the agent themselves (as their own player row).
        List<Player> allForStats = playerRepository.findAll();
        Map<Long, Player> statsById = allForStats.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        List<Player> agentPlayers = new ArrayList<>();
        agentPlayers.add(agent);
        allForStats.stream()
            .filter(p -> inAgentBook(p, agentId, statsById))
            .forEach(agentPlayers::add);

        // Game results grouped by player id (sub-players + the agent's own play)
        Map<Long, List<GameResult>> resultsByPlayer = agentAndOwnResults(agentId).stream()
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
                // The agent's own games carry no stored agentRakeShare — compute it from the agent's %
                // so the self row's commission matches what the balance's Agent Rake includes.
                if (player.getId().equals(agentId)) {
                    BigDecimal pct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;
                    agentShare = pct.multiply(totalRake).setScale(2, java.math.RoundingMode.HALF_UP);
                }
                BigDecimal periodPnl = rows.stream()
                    .map(AgentService::countedPnl)   // sat-to-live P&L excluded (our real number)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                // What the app (GG) shows: raw P&L including the sat-to-live win — for comparison.
                BigDecimal appPnl = rows.stream()
                    .map(AgentService::pnlOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                List<Map<String, Object>> games = new ArrayList<>();
                rows.stream()
                    .sorted((a, b) -> b.getSession().getStartTime().compareTo(a.getSession().getStartTime()))
                    .forEach(gr -> {
                        boolean satLive = isSatToLive(gr.getSession());
                        Map<String, Object> g = new LinkedHashMap<>();
                        g.put("date", gr.getSession().getStartTime().toString());
                        g.put("tableName", gr.getSession().getTableName());
                        g.put("gameType", gr.getSession().getGameType().name());
                        g.put("pnl", pnlOf(gr));
                        g.put("buyIn", gr.getBuyIn());
                        g.put("cashout", gr.getCashout());
                        g.put("rakePaid", gr.getRakePaid());
                        g.put("satToLive", satLive);
                        games.add(g);
                        // Offsetting row so a sat-to-live win nets to 0 in the agent balance.
                        if (satLive && pnlOf(gr).signum() != 0) {
                            Map<String, Object> adj = new LinkedHashMap<>();
                            adj.put("date", gr.getSession().getStartTime().toString());
                            adj.put("tableName", "↳ סאט ללייב — לא נספר");
                            adj.put("gameType", "");
                            adj.put("pnl", pnlOf(gr).negate());
                            adj.put("adjustment", true);
                            games.add(adj);
                        }
                    });
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("playerId", player.getId());
                m.put("username", player.getUsername());
                m.put("fullName", player.getFullName());
                m.put("isSelf", player.getId().equals(agentId));   // the agent's own play row
                m.put("balance", player.getBalance());
                m.put("gameCount", rows.size());
                m.put("totalRake", totalRake);
                m.put("agentShare", agentShare);
                m.put("periodPnl", periodPnl);
                m.put("appPnl", appPnl);   // GG's view (sat-to-live counted) — for reconciliation
                // Outstanding live tickets held by THIS player (shown per row in the detail).
                List<com.sevenmax.tracker.entity.LiveTicket> ptix = liveTicketRepository.findByUsedFalseAndPlayerId(player.getId());
                BigDecimal pTicketWorth = ptix.stream().map(t -> t.getWorth() != null ? t.getWorth() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal pTicketCost = ptix.stream().map(t -> t.getCost() != null ? t.getCost() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
                m.put("ticketWorth", pTicketWorth);
                m.put("ticketCost", pTicketCost);
                m.put("ticketProfit", pTicketWorth.subtract(pTicketCost));
                m.put("ticketCount", ptix.size());
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
        final LocalDate accrualFrom = from != null ? from : getLastSettlementDate();

        List<GameResult> results = agentAndOwnResults(agentId).stream()
            .filter(gr -> inRange(gr.getSession().getStartTime().toLocalDate(), accrualFrom, to))
            .collect(Collectors.toList());
        BigDecimal totalRake = results.stream()
            .map(gr -> gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal agentRake = rakePct.multiply(totalRake).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal playerPnl = results.stream().map(AgentService::countedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal appPnl = results.stream().map(AgentService::pnlOf).reduce(BigDecimal.ZERO, BigDecimal::add);

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
        m.put("appPnl", appPnl);   // GG's view (sat-to-live counted) — for reconciliation
        m.put("ticketWorth", ticketWorthForAgent(agentId));
        m.put("ticketCost", ticketCostForAgent(agentId));
        m.put("ticketProfit", ticketWorthForAgent(agentId).subtract(ticketCostForAgent(agentId)));
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
        LocalDate f = from != null ? from : getLastSettlementDate();
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

    /** The admin-set global תאריך התחשבנות אחרון, if one has been recorded - the authoritative
     *  default "from" date for agent balances and the P&L Expected Rakeback estimate. Falls back
     *  to the heuristic guess (from agents' payment history) only if never explicitly set. */
    @Transactional(readOnly = true)
    public LocalDate getLastSettlementDate() {
        return lastSettlementDateRepository.findById(1L)
            .map(LastSettlementDate::getDate)
            .orElseGet(this::computeHeuristicLastSettlementDate);
    }

    @Transactional
    public LastSettlementDate setLastSettlementDate(LocalDate date, String updatedBy) {
        if (date == null) throw new IllegalArgumentException("date is required");
        LastSettlementDate row = lastSettlementDateRepository.findById(1L).orElseGet(LastSettlementDate::new);
        row.setId(1L);
        row.setDate(date);
        row.setUpdatedBy(updatedBy);
        row.setUpdatedAt(java.time.LocalDateTime.now());
        return lastSettlementDateRepository.save(row);
    }

    /**
     * Heuristic fallback (pre-manual-setting era): take each agent's latest PAYMENT date, then return the
     * date shared by the most agents (tie → the more recent). התחשבנות is usually done for several agents
     * on the same day, so that shared date marks the start of the current open period. null if no payments.
     */
    private LocalDate computeHeuristicLastSettlementDate() {
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
        // Include every sub-agent's unsettled results so settling with the super agent clears the
        // whole book. For a plain agent the subtree is just itself, so this equals the old query.
        java.util.LinkedHashMap<Long, GameResult> byId = new java.util.LinkedHashMap<>();
        for (Long aid : subtreeAgentIds(agentId)) {
            for (GameResult gr : gameResultRepository.findUnsettledByAgentId(aid, EXPENSE_TRACKING_START)) {
                byId.put(gr.getId(), gr);
            }
        }
        return new ArrayList<>(byId.values());
    }
}
