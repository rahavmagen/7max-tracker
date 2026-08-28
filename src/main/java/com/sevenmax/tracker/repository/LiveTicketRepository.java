package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.LiveTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveTicketRepository extends JpaRepository<LiveTicket, Long> {

    boolean existsByPlayerIdAndSessionId(Long playerId, Long sessionId);

    java.util.Optional<LiveTicket> findByPlayerIdAndSessionId(Long playerId, Long sessionId);

    List<LiveTicket> findByUsedFalseOrderByWonDateDesc();

    /** Every ticket, used and unused, newest won first — for the history view. */
    List<LiveTicket> findAllByOrderByWonDateDesc();

    /** Self-heal: fill any missing sat cost from the winning game result's buy-in. Returns rows fixed. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value =
        "UPDATE live_ticket lt SET cost = gr.buy_in FROM game_results gr " +
        "WHERE lt.cost IS NULL AND gr.session_id = lt.session_id AND gr.player_id = lt.player_id " +
        "AND gr.result_amount > 0 AND gr.buy_in IS NOT NULL", nativeQuery = true)
    int backfillMissingCosts();

    /** Unused tickets for an agent or any of their players (the agent's outstanding ticket liability). */
    List<LiveTicket> findByUsedFalseAndAgentId(Long agentId);

    /** Unused tickets held by a specific player. */
    List<LiveTicket> findByUsedFalseAndPlayerId(Long playerId);
}
