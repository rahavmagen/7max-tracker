package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "player_referrals")
@Data
public class PlayerReferral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "referrer_player_id", nullable = false)
    private Player referrer;

    @Column(nullable = false)
    private String newPlayerName;

    @Column(nullable = false)
    private String newPlayerPhone;

    @Column(precision = 12, scale = 2)
    private BigDecimal depositAmount;

    private String createdByUsername;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
