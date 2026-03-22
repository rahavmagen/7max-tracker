package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "lesson_events")
@Data
public class LessonEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate eventDate;
    private String title;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
