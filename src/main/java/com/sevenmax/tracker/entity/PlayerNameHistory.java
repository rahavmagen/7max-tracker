package com.sevenmax.tracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** A record of a player's nickname change, detected when a ClubGG report shows a new nick for a stable club ID. */
@Entity
@Table(name = "player_name_history")
@Data
public class PlayerNameHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", insertable = false, updatable = false)
    private Long playerId;

    @JsonIgnore
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id")
    private Player player;

    @Column(nullable = false)
    private String oldUsername;

    @Column(nullable = false)
    private String newUsername;

    private String source; // e.g. "report 2026-07-20"

    @Column(updatable = false)
    private LocalDateTime changedAt = LocalDateTime.now();
}
