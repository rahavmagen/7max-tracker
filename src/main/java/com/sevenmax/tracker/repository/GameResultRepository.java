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

    @Query("SELECT DISTINCT g.player.id FROM GameResult g WHERE g.session.startTime >= :since")
    List<Long> findPlayerIdsWithGamesSince(@Param("since") LocalDateTime since);

    /** Players who haven't played since :cutoff (or never played) whose balance sits outside
     *  [-:threshold, +:threshold] - either owed to the club or owed to the player. Excludes
     *  agents themselves and players attached to an agent - their balance isn't collectible the
     *  same way and is tracked separately on the Agents page. */
    @Query(value =
        "SELECT p.id, p.username, p.full_name, p.balance, MAX(gs.start_time) " +
        "FROM players p " +
        "LEFT JOIN game_results gr ON gr.player_id = p.id " +
        "LEFT JOIN game_sessions gs ON gr.session_id = gs.id " +
        "WHERE COALESCE(p.is_agent, false) = false AND p.agent_id IS NULL " +
        "GROUP BY p.id, p.username, p.full_name, p.balance " +
        "HAVING (MAX(gs.start_time) IS NULL OR MAX(gs.start_time) < :cutoff) " +
        "AND (p.balance < -:threshold OR p.balance > :threshold) " +
        "ORDER BY p.balance DESC",
        nativeQuery = true)
    List<Object[]> findInactivePlayersWithBalanceOutsideRange(
        @Param("cutoff") LocalDateTime cutoff,
        @Param("threshold") java.math.BigDecimal threshold
    );

    @Query("SELECT COALESCE(SUM(g.resultAmount), 0) FROM GameResult g WHERE g.player.id = :playerId")
    BigDecimal sumResultByPlayerId(Long playerId);

    @Query("SELECT COALESCE(SUM(g.handsPlayed), 0) FROM GameResult g WHERE g.session.id = :sessionId")
    Integer sumHandsBySessionId(@Param("sessionId") Long sessionId);

    @Query("SELECT COALESCE(SUM(g.rakePaid), 0) FROM GameResult g WHERE g.session.id = :sessionId")
    java.math.BigDecimal sumRakeBySessionId(@Param("sessionId") Long sessionId);

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

    @Query("SELECT gr FROM GameResult gr WHERE gr.player.agent.id = :agentId AND gr.agentRakeShare IS NOT NULL AND gr.agentSettlement IS NULL")
    List<GameResult> findUnsettledByAgentId(@Param("agentId") Long agentId);

    @Query("SELECT gr FROM GameResult gr WHERE gr.player.agent.id = :agentId")
    List<GameResult> findAllByAgentId(@Param("agentId") Long agentId);

    @Query(value = "SELECT COALESCE(SUM(gr.rake_paid), 0) FROM game_results gr JOIN game_sessions gs ON gr.session_id = gs.id WHERE gs.start_time > :since", nativeQuery = true)
    java.math.BigDecimal sumRakeSince(@Param("since") java.time.LocalDateTime since);

    @Query(value = "SELECT gr.player_id, COALESCE(SUM(gr.rake_paid), 0) FROM game_results gr JOIN game_sessions gs ON gr.session_id = gs.id WHERE gs.start_time > :since GROUP BY gr.player_id", nativeQuery = true)
    List<Object[]> getRakePerPlayerSince(@Param("since") java.time.LocalDateTime since);

    @Query(value = "SELECT gr.player_id, COALESCE(SUM(gr.rake_paid), 0) FROM game_results gr JOIN game_sessions gs ON gr.session_id = gs.id WHERE gs.start_time >= :from AND gs.start_time < :to AND gs.game_type IN (:gameTypes) GROUP BY gr.player_id", nativeQuery = true)
    List<Object[]> getRakePerPlayerBetweenByGameTypes(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to, @Param("gameTypes") List<String> gameTypes);

    @Query(value =
        "SELECT p.id, p.username, p.full_name, COUNT(gr.id) as session_count, " +
        "COALESCE(SUM(CASE WHEN gs.game_type IN ('MTT','SNG','SPIN_GOLD') " +
        "  THEN gr.result_amount - gr.buy_in " +
        "  ELSE gr.result_amount END), 0) as total_pnl " +
        "FROM players p " +
        "JOIN game_results gr ON gr.player_id = p.id " +
        "JOIN game_sessions gs ON gr.session_id = gs.id " +
        "WHERE (:gameType IS NULL OR gs.game_type = :gameType) " +
        "GROUP BY p.id, p.username, p.full_name " +
        "ORDER BY COUNT(gr.id) DESC",
        nativeQuery = true)
    List<Object[]> getPlayerStats(@Param("gameType") String gameType);

    @Query(value =
        "SELECT p.id, p.username, p.full_name, COUNT(gr.id), MAX(gs.start_time) " +
        "FROM players p " +
        "JOIN game_results gr ON gr.player_id = p.id " +
        "JOIN game_sessions gs ON gr.session_id = gs.id " +
        "WHERE gs.start_time >= :refStart AND gs.start_time < :refEnd " +
        "AND (:gameType IS NULL OR gs.game_type = :gameType) " +
        "GROUP BY p.id, p.username, p.full_name " +
        "HAVING COUNT(gr.id) >= :minSessions " +
        "AND p.id NOT IN (" +
        "  SELECT DISTINCT gr2.player_id FROM game_results gr2 " +
        "  JOIN game_sessions gs2 ON gr2.session_id = gs2.id " +
        "  WHERE gs2.start_time >= :recentStart " +
        "  AND (:gameType IS NULL OR gs2.game_type = :gameType)" +
        ") " +
        "ORDER BY COUNT(gr.id) DESC",
        nativeQuery = true)
    List<Object[]> getInactivePlayers(
        @Param("refStart") LocalDateTime refStart,
        @Param("refEnd") LocalDateTime refEnd,
        @Param("recentStart") LocalDateTime recentStart,
        @Param("minSessions") int minSessions,
        @Param("gameType") String gameType
    );

    @Query(value =
        "SELECT gs.id AS sessionId, gs.start_time AS startTime, gs.end_time AS endTime, " +
        "gs.table_name AS tableName, gs.game_type AS gameType, " +
        "COUNT(gr.id) AS playerCount, COALESCE(SUM(gr.rake_paid), 0) AS totalRake " +
        "FROM game_sessions gs " +
        "LEFT JOIN game_results gr ON gr.session_id = gs.id " +
        "WHERE EXTRACT(DOW FROM gs.start_time) = 5 " +
        "AND EXTRACT(HOUR FROM gs.start_time) >= 18 " +
        "GROUP BY gs.id, gs.start_time, gs.end_time, gs.table_name, gs.game_type " +
        "ORDER BY gs.start_time DESC",
        nativeQuery = true)
    List<Object[]> getFridayRakeReport();
}
