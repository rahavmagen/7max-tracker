package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.GameResult;
import com.sevenmax.tracker.entity.GameSession;
import com.sevenmax.tracker.entity.LiveTicket;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.GameSessionRepository;
import com.sevenmax.tracker.repository.LiveTicketRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks live-event tickets owed to agent-side winners of "sat to live" games (see AgentService.isSatToLive).
 * The sat P&L is excluded from the agent balance, so the ticket is a separate, non-cash obligation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTicketService {

    private final GameSessionRepository gameSessionRepository;
    private final GameResultRepository gameResultRepository;
    private final PlayerRepository playerRepository;
    private final LiveTicketRepository liveTicketRepository;
    private final TransactionRepository transactionRepository;

    /** Sync every sat-to-live game. Returns how many new tickets were created. */
    @Transactional
    public int syncAll() {
        int created = 0;
        for (GameSession s : gameSessionRepository.findAll()) {
            if (AgentService.isSatToLive(s)) created += syncSessionInternal(s);
        }
        if (created > 0) log.info("Live-ticket sync created {} ticket(s)", created);
        return created;
    }

    /** Sync a single session (used when its sat-to-live checkbox is turned on). */
    @Transactional
    public int syncSession(Long sessionId) {
        GameSession s = gameSessionRepository.findById(sessionId).orElse(null);
        if (s == null || !AgentService.isSatToLive(s)) return 0;
        return syncSessionInternal(s);
    }

    private int syncSessionInternal(GameSession s) {
        int created = 0;
        LocalDate wonDate = s.getStartTime() != null ? s.getStartTime().toLocalDate() : null;
        for (GameResult gr : gameResultRepository.findBySessionId(s.getId())) {
            BigDecimal worth = gr.getResultAmount();
            if (worth == null || worth.signum() <= 0) continue;         // only winners
            Player p = gr.getPlayer();
            if (p == null) continue;
            Long agentId = agentIdFor(p);
            if (agentId == null) continue;                               // agent-side only
            Player agent = playerRepository.findById(agentId).orElse(null);
            if (agent != null && Boolean.TRUE.equals(agent.getClubManaged())) continue; // club handles these directly
            if (liveTicketRepository.existsByPlayerIdAndSessionId(p.getId(), s.getId())) continue;

            LiveTicket t = new LiveTicket();
            t.setPlayerId(p.getId());
            t.setAgentId(agentId);
            t.setSessionId(s.getId());
            t.setEventName(s.getTableName());
            t.setWorth(worth);
            t.setWonDate(wonDate);
            t.setUsed(false);
            liveTicketRepository.save(t);

            Transaction tx = new Transaction();
            tx.setPlayer(p);
            tx.setType(Transaction.Type.LIVE_TICKET_WON);
            tx.setAmount(BigDecimal.ZERO);
            tx.setTransactionDate(wonDate);
            tx.setNotes("זכה בכרטיס ללייב · " + s.getTableName() + " · ₪" + worth.toPlainString());
            tx.setCreatedByUsername("system");
            transactionRepository.save(tx);
            created++;
        }
        return created;
    }

    /** The agent responsible for a player's ticket: their agent, or themselves if they're an agent. */
    private Long agentIdFor(Player p) {
        if (p.getAgentId() != null) return p.getAgentId();
        if (Boolean.TRUE.equals(p.getIsAgent())) return p.getId();
        return null;
    }

    /** Outstanding (unused) tickets, newest first, with player + agent names. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUnused() {
        Map<Long, Player> byId = new LinkedHashMap<>();
        for (Player p : playerRepository.findAll()) byId.put(p.getId(), p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LiveTicket t : liveTicketRepository.findByUsedFalseOrderByWonDateDesc()) {
            Player p = byId.get(t.getPlayerId());
            Player agent = t.getAgentId() != null ? byId.get(t.getAgentId()) : null;
            if (agent != null && Boolean.TRUE.equals(agent.getClubManaged())) continue; // club-managed handled directly
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("playerId", t.getPlayerId());
            m.put("player", p != null ? p.getUsername() : "");
            m.put("agent", agent != null ? agent.getUsername() : "");
            m.put("eventName", t.getEventName());
            m.put("worth", t.getWorth());
            m.put("wonDate", t.getWonDate() != null ? t.getWonDate().toString() : null);
            out.add(m);
        }
        return out;
    }

    /** A single player's outstanding (unused) tickets — for their own dashboard. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForPlayer(Long playerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LiveTicket t : liveTicketRepository.findByUsedFalseAndPlayerId(playerId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("eventName", t.getEventName());
            m.put("worth", t.getWorth());
            m.put("wonDate", t.getWonDate() != null ? t.getWonDate().toString() : null);
            out.add(m);
        }
        return out;
    }

    /** Mark a ticket used: stamp it and log an informational "used" transaction. */
    @Transactional
    public void use(Long id, String byUsername) {
        LiveTicket t = liveTicketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + id));
        if (Boolean.TRUE.equals(t.getUsed())) return;
        t.setUsed(true);
        t.setUsedDate(LocalDate.now());
        t.setUsedBy(byUsername);
        liveTicketRepository.save(t);

        Player p = playerRepository.findById(t.getPlayerId()).orElse(null);
        if (p != null) {
            Transaction tx = new Transaction();
            tx.setPlayer(p);
            tx.setType(Transaction.Type.LIVE_TICKET_USED);
            tx.setAmount(BigDecimal.ZERO);
            tx.setTransactionDate(LocalDate.now());
            tx.setNotes("מימש כרטיס ללייב · " + t.getEventName());
            tx.setCreatedByUsername(byUsername != null ? byUsername : "system");
            transactionRepository.save(tx);
        }
    }

    /** Sum of unused ticket worth owed via an agent (agent + their players). */
    @Transactional(readOnly = true)
    public BigDecimal ticketWorthForAgent(Long agentId) {
        return liveTicketRepository.findByUsedFalseAndAgentId(agentId).stream()
                .map(t -> t.getWorth() != null ? t.getWorth() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
