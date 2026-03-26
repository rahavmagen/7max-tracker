package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "player_transfers")
@Data
public class PlayerTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // null means "CLUB"
    @ManyToOne
    @JoinColumn(name = "from_player_id")
    private Player fromPlayer;

    // null means "CLUB"
    @ManyToOne
    @JoinColumn(name = "to_player_id")
    private Player toPlayer;

    @ManyToOne
    @JoinColumn(name = "from_bank_account_id")
    private BankAccount fromBankAccount;

    @ManyToOne
    @JoinColumn(name = "to_bank_account_id")
    private BankAccount toBankAccount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Transaction.Method method;

    private String notes;

    private Boolean confirmed = false;

    private LocalDate transferDate;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime confirmedAt;

    private String confirmedBy;

    private String createdByUsername;
}
