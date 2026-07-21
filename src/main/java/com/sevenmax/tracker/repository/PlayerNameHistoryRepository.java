package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.PlayerNameHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlayerNameHistoryRepository extends JpaRepository<PlayerNameHistory, Long> {
    List<PlayerNameHistory> findByPlayerIdOrderByChangedAtDesc(Long playerId);
}
