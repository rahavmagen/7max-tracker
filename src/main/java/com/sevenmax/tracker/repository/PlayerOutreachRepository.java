package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.PlayerOutreach;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlayerOutreachRepository extends JpaRepository<PlayerOutreach, Long> {

    /**
     * All outreach rows for the given players, newest first — the caller takes the first row per
     * player as the "latest contact" for cooldown checks and last-contacted display.
     */
    List<PlayerOutreach> findByPlayerIdInOrderByHandledAtDesc(Collection<Long> playerIds);

    List<PlayerOutreach> findByPlayerIdOrderByHandledAtDesc(Long playerId);
}
