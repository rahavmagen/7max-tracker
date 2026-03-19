package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByUsername(String username);
    Optional<Player> findByUsernameIgnoreCase(String username);
    Optional<Player> findByClubPlayerId(String clubPlayerId);
    boolean existsByUsername(String username);
}
