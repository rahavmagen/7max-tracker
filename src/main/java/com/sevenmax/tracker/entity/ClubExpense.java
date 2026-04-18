package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "club_expenses")
@Data
public class ClubExpense {

    public enum PaidBy { ADMIN, CLUB }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private LocalDate expenseDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaidBy paidBy;

    // Which admin paid (ADMIN case only)
    private String adminUser;

    // Which bank account (CLUB case, or bank used to repay ADMIN)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_account_id")
    private BankAccount bankAccount;

    @Column(nullable = false)
    private boolean settled = false;

    private LocalDate settledAt;

    private String settledBy;

    private String createdBy;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // "NO_VAT" or "WITH_VAT" — set when expense is paid (or on creation for CLUB type)
    private String vatType;

    // Which admin wallet or bank account paid this expense (set when paid)
    private String paidFromAdminUsername;
    private Long paidFromBankAccountId;
}
