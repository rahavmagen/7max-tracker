package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.TicketGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketGrantRepository extends JpaRepository<TicketGrant, Long> {
    List<TicketGrant> findByAssetIdOrderByGrantedAtDesc(Long assetId);
}
