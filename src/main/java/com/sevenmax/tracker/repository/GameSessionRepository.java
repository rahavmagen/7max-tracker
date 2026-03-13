package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GameSessionRepository extends JpaRepository<GameSession, Long> {
    List<GameSession> findByReportId(Long reportId);
}
