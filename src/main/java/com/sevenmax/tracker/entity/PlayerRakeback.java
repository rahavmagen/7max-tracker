package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One per-game-type rakeback deal for a player: a percentage of the rake the player pays in a
 * specific game type, effective from a start date. A player can have several (one per game type).
 */
@Entity
@Table(name = "player_rakeback")
@Data
public class PlayerRakeback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "game_type", nullable = false)
    private String gameType;   // a GameSession.GameType name (NLH, PLO, MTT, ...)

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal percentage;   // fraction, e.g. 0.3000 = 30%

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
}
