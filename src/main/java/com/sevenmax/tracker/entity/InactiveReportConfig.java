package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Singleton (id = 1) holding the criteria the weekly inactive-players WhatsApp job uses.
 * Written by the "Save as weekly criteria" button on the Inactive Players page.
 */
@Entity
@Table(name = "inactive_report_config")
@Data
public class InactiveReportConfig {

    @Id
    private Long id = 1L;

    @Column(name = "recent_days", nullable = false)
    private Integer recentDays = 7;

    @Column(name = "lookback_days", nullable = false)
    private Integer lookbackDays = 30;

    @Column(name = "min_sessions", nullable = false)
    private Integer minSessions = 10;

    @Column(name = "game_type")
    private String gameType;   // null = all types

    @Column(name = "cooldown_days", nullable = false)
    private Integer cooldownDays = 7;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Guards against sending the weekly nudge twice on the same day - e.g. if a deploy's rolling
    // restart briefly overlaps old and new instances right around the Sunday 10:00 trigger.
    @Column(name = "last_nudge_sent_date")
    private LocalDate lastNudgeSentDate;
}
