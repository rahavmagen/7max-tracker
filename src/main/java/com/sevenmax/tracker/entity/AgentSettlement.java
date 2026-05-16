package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_settlements")
@Data
public class AgentSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Player agent;

    private LocalDate fromDate;
    private LocalDate toDate;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalRake = BigDecimal.ZERO; // sum of rakePaid from covered game_results

    @Column(precision = 12, scale = 2)
    private BigDecimal agentShare = BigDecimal.ZERO; // sum of agentRakeShare — actual payment amount

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_expense_id")
    private AdminExpense adminExpense;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
