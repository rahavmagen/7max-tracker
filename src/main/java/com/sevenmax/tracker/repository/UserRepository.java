package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsername(String username);
    Optional<User> findByPlayerId(Long playerId);

    @Modifying
    @Query("UPDATE User u SET u.player = null WHERE u.player IS NOT NULL")
    void detachAllPlayers();
}
