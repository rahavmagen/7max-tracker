package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.PlayerReferral;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlayerReferralRepository extends JpaRepository<PlayerReferral, Long> {
    List<PlayerReferral> findAllByOrderByCreatedAtDesc();
}
