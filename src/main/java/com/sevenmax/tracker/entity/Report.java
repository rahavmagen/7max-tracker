package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "reports")
@Data
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalRake = BigDecimal.ZERO;

    @ManyToOne
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(updatable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();

    private String fileName;
    private String filePath;

    /** Transient: populated after upload, not persisted */
    @Transient
    private List<Map<String, String>> leftClub;

    /** Transient: players who were stale but found in this upload's balance (recovered) */
    @Transient
    private List<Map<String, String>> recovered;

    /** Transient: chip balance mismatch (null if ok) */
    @Transient
    private java.math.BigDecimal chipMismatch;

    @Transient
    private java.math.BigDecimal chipMismatchExpected;

    @Transient
    private java.math.BigDecimal chipMismatchActual;
}
