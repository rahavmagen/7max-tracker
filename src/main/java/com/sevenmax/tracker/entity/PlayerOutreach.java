package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One outreach/contact event for an inactive player (the re-engagement CRM log).
 * A player can have many rows over time; the cooldown check looks at the most recent handled_at.
 */
@Entity
@Table(name = "player_outreach")
@Data
public class PlayerOutreach {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "handled_at", nullable = false)
    private LocalDateTime handledAt;

    @Column(name = "handled_by")
    private String handledBy;

    @Column(columnDefinition = "text")
    private String note;
}
