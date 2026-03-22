package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.LessonEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface LessonEventRepository extends JpaRepository<LessonEvent, Long> {
    Optional<LessonEvent> findFirstByEventDateGreaterThanEqualOrderByEventDateAsc(LocalDate date);
}
