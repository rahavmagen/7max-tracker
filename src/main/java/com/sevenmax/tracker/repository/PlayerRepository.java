package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByUsername(String username);
    Optional<Player> findByClubPlayerId(String clubPlayerId);
    boolean existsByUsername(String username);

    @Query("SELECT p FROM Player p WHERE LOWER(p.username) = LOWER(:username)")
    List<Player> findByUsernameCaseInsensitive(@Param("username") String username);
}
