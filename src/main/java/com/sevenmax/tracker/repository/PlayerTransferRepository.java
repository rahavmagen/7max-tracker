package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.PlayerTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlayerTransferRepository extends JpaRepository<PlayerTransfer, Long> {
    List<PlayerTransfer> findByConfirmedFalseOrderByCreatedAtDesc();
}
