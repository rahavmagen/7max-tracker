package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "lesson_registrations")
@Data
public class LessonRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "lesson_event_id")
    private LessonEvent lessonEvent;

    private String username;
    private String fullName;
    private String phone;

    @Column(updatable = false)
    private LocalDateTime registeredAt = LocalDateTime.now();
}
