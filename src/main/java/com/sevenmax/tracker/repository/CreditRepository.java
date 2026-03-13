package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.Credit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CreditRepository extends JpaRepository<Credit, Long> {
    List<Credit> findByPlayerIdOrderByCreatedAtDesc(Long playerId);
}
