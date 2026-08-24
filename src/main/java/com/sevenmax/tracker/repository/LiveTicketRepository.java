package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.LiveTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveTicketRepository extends JpaRepository<LiveTicket, Long> {

    boolean existsByPlayerIdAndSessionId(Long playerId, Long sessionId);

    java.util.Optional<LiveTicket> findByPlayerIdAndSessionId(Long playerId, Long sessionId);

    List<LiveTicket> findByUsedFalseOrderByWonDateDesc();

    /** Unused tickets for an agent or any of their players (the agent's outstanding ticket liability). */
    List<LiveTicket> findByUsedFalseAndAgentId(Long agentId);

    /** Unused tickets held by a specific player. */
    List<LiveTicket> findByUsedFalseAndPlayerId(Long playerId);
}
