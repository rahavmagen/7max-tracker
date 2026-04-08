package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "import_summary")
@Data
public class ImportSummary {

    @Id
    private Long id = 1L; // singleton row, always overwritten

    // From הוצאות column H (גלגל / will expenses)
    @Column(precision = 12, scale = 2)
    private BigDecimal willExpense = BigDecimal.ZERO;

    // From הוצאות column C (general expenses)
    @Column(precision = 12, scale = 2)
    private BigDecimal generalExpenses = BigDecimal.ZERO;

    // From מעקב הפקדות ומשיכות deposit columns C+D+E
    @Column(precision = 12, scale = 2)
    private BigDecimal bankDeposits = BigDecimal.ZERO;

    private LocalDateTime lastUpdated = LocalDateTime.now();

    // Chip snapshot from the most recently uploaded XLS (latest report only)
    @Column(precision = 12, scale = 2)
    private BigDecimal lastReportChipsTotal = BigDecimal.ZERO;

    private java.time.LocalDate lastReportDate; // periodEnd of latest uploaded report

    // Snapshot of total credit across all players at the moment of last XLS upload
    @Column(precision = 12, scale = 2)
    private BigDecimal snapshotCreditTotal = BigDecimal.ZERO;

    // Computed live from PROMOTION transactions — not stored in DB
    @Transient
    private BigDecimal promotionsTotal = BigDecimal.ZERO;

    // Computed live from CHIP_PROMO transactions — not stored in DB
    @Transient
    private BigDecimal chipPromoTotal = BigDecimal.ZERO;
}
