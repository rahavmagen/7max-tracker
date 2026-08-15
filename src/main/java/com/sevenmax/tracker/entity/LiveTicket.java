package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A live-event ticket owed to an agent-side winner of a "sat to live event" game. The club owes them
 * a ticket (not money) — the sat P&L is excluded from the agent balance, so this tracks the obligation
 * separately. One per (player, session); created idempotently by LiveTicketService.sync().
 */
@Entity
@Table(name = "live_ticket", uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "session_id"}))
@Data
public class LiveTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;      // the winner

    @Column(name = "agent_id")
    private Long agentId;       // the winner's agent (= playerId when the winner IS the agent)

    @Column(name = "session_id", nullable = false)
    private Long sessionId;     // the sat-to-live game

    @Column(name = "event_name")
    private String eventName;

    @Column(precision = 12, scale = 2)
    private BigDecimal worth;   // the winner's result_amount (seat value)

    @Column(name = "won_date")
    private LocalDate wonDate;

    @Column(nullable = false)
    private Boolean used = false;

    @Column(name = "used_date")
    private LocalDate usedDate;

    @Column(name = "used_by")
    private String usedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
