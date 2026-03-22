package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.LessonRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LessonRegistrationRepository extends JpaRepository<LessonRegistration, Long> {
    List<LessonRegistration> findByLessonEventId(Long lessonEventId);
    Optional<LessonRegistration> findByLessonEventIdAndUsername(Long lessonEventId, String username);
    boolean existsByLessonEventIdAndUsername(Long lessonEventId, String username);
}
