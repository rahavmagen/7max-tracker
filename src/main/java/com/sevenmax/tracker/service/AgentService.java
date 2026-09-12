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

    /** Every player id mapped to their super-agent's username, or null if they have none
     *  (including a top-level agent who has nobody above them). Loads the player list once
     *  so this is safe to call for a whole result set without N+1 queries. */
    @Transactional(readOnly = true)
    public Map<Long, String> resolveSuperAgentNames() {
        List<Player> all = playerRepository.findAll();
        Map<Long, Player> byId = all.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        Map<Long, String> result = new HashMap<>();
        for (Player p : all) {
            Player top = resolveTopAgent(p, byId);
            result.put(p.getId(), (top != null && !top.getId().equals(p.getId())) ? top.getUsername() : null);
        }
        return result;
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

    public List<Map<String, Object>> getAllAgentsSummary(LocalDate from, LocalDate to) {
        List<Player> allPlayers = playerRepository.findAll();
        Map<Long, Player> byId = allPlayers.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        return allPlayers.stream()
            .filter(p -> isTopLevelAgent(p, byId))
            .map(agent -> {
                BigDecimal agentPct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;
                long playerCount = allPlayers.stream()
                    .filter(p -> inAgentBook(p, agent.getId(), byId))
                    .count();

                // Games played and total club rake by this agent's players AND the agent's own play,
                // over the date range (the agent is treated like one of their own players).
                final Long agentId = agent.getId();
                final LocalDate effectiveFrom = resolveAgentReportingFrom(agentId, from);
                List<GameResult> allResultsForBalance = agentAndOwnResults(agentId);
                List<GameResult> results = allResultsForBalance.stream()
                    .filter(gr -> {
                        LocalDate d = gr.getSession().getStartTime().toLocalDate();
                        if (effectiveFrom != null && d.isBefore(effectiveFrom)) return false;
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

                LocalDate lastSettlement = resolveAgentLastCheckpoint(agentId);
                // Informational only (does NOT feed currentBalance — a Settle can be partial, so the
                // true running balance must stay anchored to the last full OPENING reset). Shows P&L
                // purely for games from the day AFTER the last checkpoint through today (the checkpoint
                // day itself is already reflected in what was settled then, so it's excluded here to
                // avoid double-counting it), for an at-a-glance "what's happened since we last settled
                // with this agent" independent of any table date filter.
                LocalDate sinceSettlementFrom = lastSettlement != null ? lastSettlement.plusDays(1) : null;
                BigDecimal pnlSinceSettlement = allResultsForBalance.stream()
                    .filter(gr -> {
                        LocalDate d = gr.getSession().getStartTime().toLocalDate();
                        if (sinceSettlementFrom != null && d.isBefore(sinceSettlementFrom)) return false;
                        return !d.isAfter(LocalDate.now());
                    })
                    .map(AgentService::countedPnl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", agent.getId());
                m.put("username", agent.getUsername());
                m.put("fullName", agent.getFullName());
                m.put("phone", agent.getPhone());
                m.put("rakePercentage", agent.getAgentRakePercentage());
                m.put("clubManaged", Boolean.TRUE.equals(agent.getClubManaged()));
                // Balance reconciles with the shown columns: starting + agentRake + P&L − payments, over [from,to].
                BigDecimal rakePct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;
                BigDecimal agentRake = rakePct.multiply(totalRake).setScale(2, java.math.RoundingMode.HALF_UP);
                AgentLedgerEntry openingE = agentLedgerEntryRepository
                    .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.OPENING).stream()
                    .max(Comparator.comparing(AgentLedgerEntry::getId))
                    .orElse(null);
                BigDecimal startBal = openingE != null ? openingE.getAmount() : BigDecimal.ZERO;
                BigDecimal pmts = agentLedgerEntryRepository
                    .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.PAYMENT).stream()
                    .filter(e -> inRange(e.getEffectiveDate(), effectiveFrom, to))
                    .map(AgentLedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                // currentBalance: starting + this period's rake + this period's P&L − this period's
                // payments, where "period" is [from,to] as chosen by the caller (defaulting to since
                // this agent's own opening date through today when the caller passes no range) - so it
                // reflects exactly what's shown on screen, not always "as of right now".
                BigDecimal currentBal = startBal.add(agentRake).add(periodPnl).subtract(pmts);

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
                m.put("pnlSinceSettlement", pnlSinceSettlement);
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
        result.put("players", playersList);
        result.put("settlementHistory", historyList);
        return result;
    }

    /** Manually record an agent-rake Club Expense for an arbitrary amount - for corrections/one-off
     *  amounts that don't come from the daily per-game accrual (see createDailyAgentRakeExpenses). */
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

    /** Creates one idempotent AdminExpense(AGENT) per top-level agent per given calendar day, for the
     *  rake they earned that day (their own rake %, applied to their whole subtree's rakePaid for that
     *  day). Called once at the end of an XLS upload, for
     *  every distinct session date involved. Re-uploading the same day never double-counts, since each
     *  expense is keyed by a sourceRef of "AGENTRAKE:{agentId}:{date}". */
    @Transactional
    public void createDailyAgentRakeExpenses(Set<LocalDate> sessionDates) {
        if (sessionDates == null || sessionDates.isEmpty()) return;
        List<Player> allPlayers = playerRepository.findAll();
        Map<Long, Player> byId = allPlayers.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        List<Player> topAgents = allPlayers.stream()
            .filter(p -> isTopLevelAgent(p, byId))
            .collect(Collectors.toList());

        for (Player agent : topAgents) {
            BigDecimal rakePct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;
            if (rakePct.signum() <= 0) continue;
            List<GameResult> subtreeResults = agentAndOwnResults(agent.getId());
            for (LocalDate date : sessionDates) {
                String sourceRef = "AGENTRAKE:" + agent.getId() + ":" + date;
                if (adminExpenseRepository.existsBySourceRef(sourceRef)) continue;
                BigDecimal dayRake = subtreeResults.stream()
                    .filter(gr -> date.equals(gr.getSession().getStartTime().toLocalDate()))
                    .map(gr -> gr.getRakePaid() != null ? gr.getRakePaid() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (dayRake.signum() <= 0) continue;
                BigDecimal agentShare = rakePct.multiply(dayRake).setScale(2, java.math.RoundingMode.HALF_UP);
                if (agentShare.signum() <= 0) continue;

                AdminExpense exp = new AdminExpense();
                exp.setAdminUsername(agent.getUsername());
                exp.setAmount(agentShare);
                exp.setNotes("Agent rake — " + date);
                exp.setExpenseDate(date);
                exp.setCreatedBy("Import");
                exp.setExpenseType("AGENT");
                exp.setSourceRef(sourceRef);
                adminExpenseRepository.save(exp);
            }
        }
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

    /** True for a sat-to-live game the player actually WON (result > 0) — they got a live ticket. */
    private static boolean isSatToLiveWin(GameResult gr) {
        return isSatToLive(gr.getSession()) && gr.getResultAmount() != null && gr.getResultAmount().signum() > 0;
    }

    /** P&L that counts toward the agent balance. A sat-to-live WIN's resultAmount (the ticket's face
     *  value) is excluded — it's paid via a live ticket, not cash — but the buy-in she actually paid
     *  is real money and still counts as a loss, same as a player who entered the sat and lost. */
    private static BigDecimal countedPnl(GameResult gr) {
        if (isSatToLiveWin(gr)) {
            BigDecimal buyIn = gr.getBuyIn() != null ? gr.getBuyIn() : BigDecimal.ZERO;
            return buyIn.negate();
        }
        return pnlOf(gr);
    }

    /** Per-player rake stats for an agent, with optional date filter (all results, settled+unsettled) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerStats(Long agentId, LocalDate from, LocalDate to) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        // An explicit caller date always wins; with none, default to the day AFTER this agent's own
        // last settlement checkpoint (same one shown in the "Last Settlement" column) instead of
        // all-time - the checkpoint day itself is excluded since it's already reflected in whatever
        // was settled that day.
        LocalDate checkpoint = resolveAgentLastCheckpoint(agentId);
        final LocalDate effectiveFrom = from != null ? from : (checkpoint != null ? checkpoint.plusDays(1) : null);

        // Everyone in this (super) agent's book PLUS the agent themselves (as their own player row).
        List<Player> allForStats = playerRepository.findAll();
        Map<Long, Player> statsById = allForStats.stream().collect(Collectors.toMap(Player::getId, p -> p, (a, b) -> a));
        List<Player> agentPlayers = new ArrayList<>();
        agentPlayers.add(agent);
        allForStats.stream()
            .filter(p -> inAgentBook(p, agentId, statsById))
            .forEach(agentPlayers::add);

        List<GameResult> allBookResults = agentAndOwnResults(agentId);
        // Most recent game overall per player, independent of the from/to filter below — so
        // "last played" still reflects reality even when the page is scoped to an older period.
        Map<Long, java.time.LocalDateTime> lastPlayedByPlayer = allBookResults.stream()
            .collect(Collectors.toMap(gr -> gr.getPlayer().getId(), gr -> gr.getSession().getStartTime(),
                (a, b) -> a.isAfter(b) ? a : b));

        // Game results grouped by player id (sub-players + the agent's own play)
        Map<Long, List<GameResult>> resultsByPlayer = allBookResults.stream()
            .filter(gr -> {
                LocalDate d = gr.getSession().getStartTime().toLocalDate();
                if (effectiveFrom != null && d.isBefore(effectiveFrom)) return false;
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
                        boolean satLiveWin = isSatToLiveWin(gr);
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
                        // Offsetting row so a sat-to-live WIN nets to countedPnl (real buy-in loss,
                        // not zero) instead of the GG-reported ticket-value "profit".
                        BigDecimal adjAmount = countedPnl(gr).subtract(pnlOf(gr));
                        if (satLiveWin && adjAmount.signum() != 0) {
                            Map<String, Object> adj = new LinkedHashMap<>();
                            adj.put("date", gr.getSession().getStartTime().toString());
                            adj.put("tableName", "↳ סאט ללייב — הזכייה היא כרטיס, לא מזומן");
                            adj.put("gameType", "");
                            adj.put("pnl", adjAmount);
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
                m.put("lastPlayedDate", lastPlayedByPlayer.get(player.getId()));
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
    /** The agent's own latest OPENING ledger entry (their personal reconciliation baseline), or null
     *  if they've never had one set. Per-agent by construction - different agents can have different
     *  opening dates, independent of one another and of the club-wide הת חשבנות date. */
    private AgentLedgerEntry latestOpening(Long agentId) {
        return agentLedgerEntryRepository
            .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.OPENING).stream()
            .max(Comparator.comparing(AgentLedgerEntry::getId))
            .orElse(null);
    }

    /** The agent's own latest PAYMENT ledger entry (created by the "Settle" button), or null. */
    private AgentLedgerEntry latestPayment(Long agentId) {
        List<AgentLedgerEntry> payments = agentLedgerEntryRepository
            .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.PAYMENT);
        if (payments == null) return null;
        return payments.stream()
            .max(Comparator.comparing(AgentLedgerEntry::getId))
            .orElse(null);
    }

    /** This agent's own last settlement checkpoint, for DISPLAY only: whichever is more recent of
     *  their latest OPENING date and their latest PAYMENT (Settle) date. Null if neither exists yet.
     *  Deliberately NOT used to anchor currentBalance's running total — a Settle can be a partial
     *  payment, so the true balance must keep accruing from the last full OPENING reset regardless
     *  of how many settles happened since. See resolveAgentReportingFrom for that authoritative cutoff. */
    LocalDate resolveAgentLastCheckpoint(Long agentId) {
        AgentLedgerEntry opening = latestOpening(agentId);
        AgentLedgerEntry payment = latestPayment(agentId);
        LocalDate openingDate = opening != null ? opening.getEffectiveDate() : null;
        LocalDate paymentDate = payment != null ? payment.getEffectiveDate() : null;
        if (openingDate == null) return paymentDate;
        if (paymentDate == null) return openingDate;
        return openingDate.isAfter(paymentDate) ? openingDate : paymentDate;
    }

    /** Resolves the "from" date for an agent's period figures: an explicit caller date always wins
     *  (used as typed - a caller picking a date range means it literally). With none, each agent
     *  defaults to the day AFTER THEIR OWN latest OPENING entry's date (so agents reconciled on
     *  different days don't get folded into one shared cutoff), falling back to the day after the
     *  club-wide last settlement date only for an agent that has never had an OPENING entry.
     *  The +1 day matters: an OPENING's effectiveDate is the day the settlement itself happened
     *  (what an admin naturally types), and that day's activity is already reflected in the
     *  balance being carried forward - counting it again in the new period would double it. */
    LocalDate resolveAgentReportingFrom(Long agentId, LocalDate callerFrom) {
        if (callerFrom != null) return callerFrom;
        AgentLedgerEntry opening = latestOpening(agentId);
        LocalDate anchor = opening != null ? opening.getEffectiveDate() : getLastSettlementDate();
        return anchor != null ? anchor.plusDays(1) : null;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAgentBalance(Long agentId, LocalDate from, LocalDate to) {
        Player agent = playerRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        BigDecimal rakePct = agent.getAgentRakePercentage() != null ? agent.getAgentRakePercentage() : BigDecimal.ZERO;

        // Starting balance = latest OPENING entry (the carry from the last התחשבנות).
        AgentLedgerEntry baseline = agentLedgerEntryRepository
            .findByAgentIdAndType(agentId, AgentLedgerEntry.Type.OPENING).stream()
            .max(Comparator.comparing(AgentLedgerEntry::getId))
            .orElse(null);
        LocalDate openingDate = baseline != null ? baseline.getEffectiveDate() : null;
        BigDecimal startingBalance = baseline != null ? baseline.getAmount() : BigDecimal.ZERO;

        // "This period" figures (rakebackSince/playerPnlSince/paymentsSince below) are filterable by
        // the caller's from/to, defaulting to since the club-wide last התחשבנות. currentBalance below
        // is built from these same period-scoped figures, so it reflects this exact window too.
        final LocalDate accrualFrom = resolveAgentReportingFrom(agentId, from);

        List<GameResult> allResults = agentAndOwnResults(agentId);
        List<GameResult> results = allResults.stream()
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

        // currentBalance: starting + this period's rake + this period's P&L − this period's payments,
        // where "period" is [from,to] as chosen by the caller (defaulting to since this agent's own
        // opening date through today) - so it reflects exactly the window being browsed.
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
        // Each agent's currentBalance is anchored to ITS OWN opening date (from=null below), not this
        // shared f/to - so the club-wide total here always reflects each agent's true position, never
        // skewed by a global date that doesn't match any particular agent's own last opening.
        BigDecimal total = playerRepository.findAll().stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsAgent()) && !Boolean.TRUE.equals(p.getClubManaged()))
            .map(a -> (BigDecimal) getAgentBalance(a.getId(), null, null).get("currentBalance"))
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
}
