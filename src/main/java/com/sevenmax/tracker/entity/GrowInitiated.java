package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "grow_initiated")
@Data
public class GrowInitiated {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String paymentLinkProcessId;

    private String paymentLinkProcessToken;

    @Column(nullable = false)
    private Long playerId;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    private Boolean processed = false;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
