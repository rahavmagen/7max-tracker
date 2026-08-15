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

    @Column(precision = 12, scale = 2)
    private BigDecimal entryFee; // buy-in + fee per entry (G+H from MTT Statistics)

    private Integer entryCount;

    // Manual override marking this session as a "satellite to a live event" (double-up). The club
    // fakes these with a dummy target that's cancelled, so their P&L must NOT count toward the
    // agent balance (the player was already paid via the live ticket). Effective flag also matches
    // by name — see AgentService.isSatToLive.
    @Column(name = "sat_to_live")
    private Boolean satToLive;

    @ManyToOne
    @JoinColumn(name = "report_id")
    private Report report;

    public enum GameType {
        NLH, PLO, PLO4, PLO5, PLO6, SNG, MTT, AoF, SPIN_GOLD
    }
}
