package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A credit/loss ledger entry for a Tournament Horse - like a write-off, but tied to a specific
 * horse and summed into their running deficit (how much they still need to win before the club
 * starts splitting profit with them 50/50).
 */
@Entity
@Table(name = "horse_transactions")
@Data
public class HorseTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    private String notes;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
