package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "league_session_config")
@Data
public class LeagueSessionConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "game_session_id")
    private GameSession session;

    private Boolean included = false;

    private Integer handsMultiplier = 1;

    private Integer profitMultiplier = 1;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
