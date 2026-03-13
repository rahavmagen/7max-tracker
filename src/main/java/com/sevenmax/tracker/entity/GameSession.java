package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "game_sessions")
@Data
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tableName;

    @Enumerated(EnumType.STRING)
    private GameType gameType;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Column(precision = 12, scale = 2)
    private BigDecimal rakeTotal = BigDecimal.ZERO;

    @ManyToOne
    @JoinColumn(name = "report_id")
    private Report report;

    public enum GameType {
        NLH, PLO, PLO5, PLO6, SNG, MTT, AoF, SPIN_GOLD
    }
}
