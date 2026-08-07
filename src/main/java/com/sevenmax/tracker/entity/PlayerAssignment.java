package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Current re-engagement owner for a player (one row per player, upserted) - separate from the
 * contact history in {@link PlayerOutreach}. Assigning doesn't create an outreach event; it just
 * says "this admin is responsible for following up."
 */
@Entity
@Table(name = "player_assignments")
@Data
public class PlayerAssignment {

    @Id
    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "assigned_admin_username")
    private String assignedAdminUsername;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "assigned_by")
    private String assignedBy;
}
