package com.sevenmax.tracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "players")
@Data
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
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

    // Payment methods the player accepts
    private Boolean bitEnabled = false;
    private Boolean payboxEnabled = false;
    private Boolean kashcashEnabled = false;
    private Boolean cashEnabled = false;
    private Boolean bankTransferEnabled = false;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Agent system
    private Boolean isAgent = false;

    @Column(precision = 6, scale = 4)
    private BigDecimal agentRakePercentage; // e.g. 0.3000 = 30%

    // Rakeback
    @Column(precision = 6, scale = 4)
    private BigDecimal rakebackPercentage; // e.g. 0.2000 = 20%

    private LocalDate rakebackSince; // start date for rakeback eligibility

    @Column(name = "agent_id", insertable = false, updatable = false)
    private Long agentId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Player agent; // self-referential FK, null if no agent
}
