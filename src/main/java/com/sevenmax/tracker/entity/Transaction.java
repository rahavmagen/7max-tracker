package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Data
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Method method;

    @ManyToOne
    @JoinColumn(name = "processed_by")
    private User processedBy;

    private String notes;
    private LocalDate transactionDate;

    @Column(unique = false)
    private String sourceRef; // for dedup of imported trade records, e.g. "TRADE:2026-03-08 11:02:59:1326-0732"

    private String createdByUsername;

    private Boolean pendingConfirmation = false;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Type {
        DEPOSIT, WITHDRAWAL, CREDIT, REPAYMENT, WHEEL_EXPENSE
    }

    public enum Method {
        BIT, PAYBOX, KASHCASH, CASH, BANK_TRANSFER, OTHER
    }
}
