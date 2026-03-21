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
}
