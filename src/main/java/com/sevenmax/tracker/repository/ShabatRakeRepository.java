package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.ShabatRake;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ShabatRakeRepository extends JpaRepository<ShabatRake, Long> {
    Optional<ShabatRake> findTopByOrderByCreatedAtDesc();
    List<ShabatRake> findAllByOrderByCreatedAtDesc();
}
