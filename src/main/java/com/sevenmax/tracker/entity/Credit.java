package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "credits")
@Data
public class Credit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @ManyToOne
    @JoinColumn(name = "given_by")
    private User givenBy;

    private String notes;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
