package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Singleton (id = 1): the admin-set תאריך התחשבנות אחרון (last settlement date), replacing the
 * old heuristic guess based on agents' last payment. Used as the system-wide default "from" date
 * for agent balance calculations and for the P&L page's Expected Rakeback estimate.
 */
@Entity
@Table(name = "last_settlement_date")
@Data
public class LastSettlementDate {

    @Id
    private Long id = 1L;

    private LocalDate date;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
