package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "players")
@Data
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username; // ClubGG nickname

    private String fullName;
    private String phone;

    @Column(unique = true)
    private String clubPlayerId; // e.g. "2163-3811"

    // Current chips in ClubGG system (from Club Member Balance tab)
    @Column(precision = 12, scale = 2)
    private BigDecimal currentChips = BigDecimal.ZERO;

    // Total credit given by managers (sum of C+D+E from מעקב קרדיטים)
    @Column(precision = 12, scale = 2)
    private BigDecimal creditTotal = BigDecimal.ZERO;

    // P&L = currentChips - creditTotal (negative = lost money)
    @Column(precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    // Additional deposits/withdrawals (outside ClubGG)
    @Column(precision = 12, scale = 2)
    private BigDecimal depositsTotal = BigDecimal.ZERO;

    // Date the chips balance was last set (from report periodEnd, represents 00:00 of that day)
    private LocalDate chipsAsOf;

    // True if this player was NOT present in the most recent XLS upload
    private Boolean chipsStale = false;

    private Boolean active = true;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
