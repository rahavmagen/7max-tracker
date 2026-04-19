package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "admin_wallet_starting_balances")
@Data
public class AdminWalletStartingBalance {

    @Id
    @Column(name = "admin_username")
    private String adminUsername;

    @Column(precision = 12, scale = 2)
    private BigDecimal cashAmount = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal bitAmount = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal payboxAmount = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal kashcashAmount = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal otherAmount = BigDecimal.ZERO;

    private String notes;

    @Column(updatable = false)
    private LocalDateTime setAt = LocalDateTime.now();
}
