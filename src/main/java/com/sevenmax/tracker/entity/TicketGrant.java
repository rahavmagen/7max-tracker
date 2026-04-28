package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_grants")
@Data
public class TicketGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "ticket_asset_id")
    private TicketAsset asset;

    @ManyToOne(optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    /** CHIPS or LIVE */
    @Column(nullable = false)
    private String grantType;

    /** USED or NOT_USED */
    @Column(nullable = false)
    private String status = "NOT_USED";

    @Column(updatable = false)
    private LocalDateTime grantedAt = LocalDateTime.now();

    private LocalDateTime usedAt;

    private String createdByUsername;
}
