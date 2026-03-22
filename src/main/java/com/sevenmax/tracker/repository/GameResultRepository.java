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

    @Query("SELECT DISTINCT g.player.id FROM GameResult g")
    List<Long> findPlayerIdsWithGameResults();

    @Query("SELECT DISTINCT g.player FROM GameResult g WHERE g.session.startTime >= :since")
    List<com.sevenmax.tracker.entity.Player> findActivePlayers(@Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(g.resultAmount), 0) FROM GameResult g WHERE g.player.id = :playerId")
    BigDecimal sumResultByPlayerId(Long playerId);

    @Query(value =
        "SELECT gs.id AS sessionId, gs.start_time AS startTime, gs.table_name AS tableName, gs.game_type AS gameType, " +
        "COUNT(gr.id) AS playerCount, COALESCE(SUM(gr.hands_played), 0) AS totalHands, COALESCE(SUM(gr.rake_paid), 0) AS totalRake " +
        "FROM game_sessions gs " +
        "LEFT JOIN game_results gr ON gr.session_id = gs.id " +
        "WHERE gs.start_time >= :from AND gs.start_time < :to " +
        "GROUP BY gs.id, gs.start_time, gs.table_name, gs.game_type " +
        "ORDER BY gs.start_time DESC",
        nativeQuery = true)
    List<Object[]> getIncomeReport(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );

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
