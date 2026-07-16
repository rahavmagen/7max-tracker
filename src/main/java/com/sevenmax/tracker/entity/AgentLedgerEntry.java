package com.sevenmax.tracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One anchor in an agent's running-balance ledger. Two kinds:
 *  - OPENING: "as of effectiveDate, the balance with this agent is amount" (+ = club owes agent).
 *             The newest OPENING is the baseline; games/payments before it are captured in the number.
 *  - PAYMENT: cash moved between club and agent. amount is signed as club -> agent
 *             (+ = we paid the agent, pays the balance down; - = the agent paid us).
 *
 * The accrued part of the balance (rakeback + players' P&L) is NOT stored — it is computed live
 * from game_results, so it self-heals when reports are deleted / re-uploaded.
 */
@Entity
@Table(name = "agent_ledger_entry")
@Data
public class AgentLedgerEntry {

    public enum Type { OPENING, PAYMENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", insertable = false, updatable = false)
    private Long agentId;

    @JsonIgnore
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Player agent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    private String notes;

    private String createdBy;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
