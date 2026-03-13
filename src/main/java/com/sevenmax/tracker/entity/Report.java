package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
}
