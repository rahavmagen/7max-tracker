package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "league_config")
@Data
public class LeagueConfig {

    @Id
    private Long id;

    private Integer minHands = 100;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
