package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Entity
@Table(name = "game_results")
@Data
public class GameResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "session_id")
    private GameSession session;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    @Column(precision = 12, scale = 2)
    private BigDecimal buyIn = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal cashout = BigDecimal.ZERO;

    private Integer handsPlayed;

    @Column(precision = 12, scale = 2)
    private BigDecimal rakePaid = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal resultAmount = BigDecimal.ZERO; // P&L from ClubGG

    private Integer tournamentPlace; // MTT/SNG only
}
