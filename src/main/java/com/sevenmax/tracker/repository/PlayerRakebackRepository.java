package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.PlayerRakeback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerRakebackRepository extends JpaRepository<PlayerRakeback, Long> {

    List<PlayerRakeback> findByPlayerIdOrderByGameTypeAsc(Long playerId);

    long deleteByIdAndPlayerId(Long id, Long playerId);
}
