package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByUsername(String username);
    boolean existsByUsername(String username);

    @Query("SELECT p FROM Player p WHERE LOWER(p.username) = LOWER(:username)")
    List<Player> findByUsernameCaseInsensitive(@Param("username") String username);

    // Fuzzy: strip spaces, underscores, hyphens before comparing (use [ _-] not \s for PG POSIX compat)
    @Query(value = "SELECT * FROM players WHERE LOWER(REGEXP_REPLACE(username, '[ _-]', '', 'g')) = LOWER(REGEXP_REPLACE(:username, '[ _-]', '', 'g'))", nativeQuery = true)
    List<Player> findByUsernameFuzzy(@Param("username") String username);

    // Alphanumeric: strip ALL non-alphanumeric chars (handles zalta vs zalta!, user.name vs username, etc.)
    @Query(value = "SELECT * FROM players WHERE LOWER(REGEXP_REPLACE(username, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(:username, '[^a-zA-Z0-9]', '', 'g'))", nativeQuery = true)
    List<Player> findByUsernameAlphanumeric(@Param("username") String username);

    @Query(value = "SELECT * FROM players WHERE REPLACE(club_player_id, '-', '') = REPLACE(:clubPlayerId, '-', '')", nativeQuery = true)
    List<Player> findByClubPlayerIdSafe(@Param("clubPlayerId") String clubPlayerId);
}
