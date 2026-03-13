package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.GameResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface GameResultRepository extends JpaRepository<GameResult, Long> {
    List<GameResult> findByPlayerIdOrderBySessionStartTimeDesc(Long playerId);
    List<GameResult> findBySessionId(Long sessionId);

    @Query("SELECT COALESCE(SUM(g.resultAmount), 0) FROM GameResult g WHERE g.player.id = :playerId")
    BigDecimal sumResultByPlayerId(Long playerId);

    @Query(value =
        "SELECT p.id AS playerId, p.username AS username, p.full_name AS fullName, " +
        "SUM(gr.hands_played) AS totalHands, COUNT(gr.id) AS sessionCount " +
        "FROM game_results gr " +
        "JOIN game_sessions gs ON gr.session_id = gs.id " +
        "JOIN players p ON gr.player_id = p.id " +
        "WHERE gs.start_time >= :from AND gs.start_time < :to " +
        "AND gs.game_type NOT IN ('MTT', 'SNG') " +
        "GROUP BY p.id, p.username, p.full_name " +
        "HAVING SUM(gr.hands_played) >= :minHands " +
        "ORDER BY SUM(gr.hands_played) DESC",
        nativeQuery = true)
    List<PlayerHandsProjection> getHandsReport(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        @Param("minHands") int minHands
    );
}
