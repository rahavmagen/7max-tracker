package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "admin_expenses")
@Data
public class AdminExpense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String adminUsername;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    private String notes;

    private LocalDate expenseDate;

    private String createdBy;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // "XLS" for import-sourced, null/absent for manual
    private String sourceRef;

    private Boolean settled = false;
    private LocalDate settledAt;
    private String settledBy;

    // "NO_VAT" or "WITH_VAT" — set when expense is paid
    private String vatType;
}
