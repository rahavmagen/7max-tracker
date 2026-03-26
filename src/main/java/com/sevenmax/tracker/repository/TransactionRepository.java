package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByPlayerIdOrderByTransactionDateDesc(Long playerId);
    boolean existsBySourceRef(String sourceRef);
    List<Transaction> findBySourceRef(String sourceRef);
    List<Transaction> findByReportId(Long reportId);

    @Query("SELECT t FROM Transaction t WHERE t.type IN :types AND t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<Transaction> findRecentByTypes(@Param("types") List<Transaction.Type> types, @Param("since") LocalDateTime since);

    List<Transaction> findByPendingConfirmationTrueOrderByCreatedAtDesc();
    Optional<Transaction> findFirstByPlayerIdAndAmountAndPendingConfirmationTrue(Long playerId, BigDecimal amount);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND (t.source_ref IS NULL OR t.source_ref NOT LIKE 'SCREEN:%') AND t.created_at > :since", nativeQuery = true)
    BigDecimal sumDepositsSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND t.source_ref = 'SCREEN:CREDIT' AND t.created_at > :since", nativeQuery = true)
    BigDecimal sumCreditsSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'WHEEL_EXPENSE' AND t.created_at > :since", nativeQuery = true)
    BigDecimal sumWheelExpensesSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT t.player_id, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND (t.source_ref IS NULL OR t.source_ref NOT LIKE 'SCREEN:%') AND t.created_at > :since GROUP BY t.player_id", nativeQuery = true)
    List<Object[]> getDepositsPerPlayerSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT t.player_id, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND t.source_ref = 'SCREEN:CREDIT' AND t.created_at > :since GROUP BY t.player_id", nativeQuery = true)
    List<Object[]> getCreditsPerPlayerSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT t.player_id, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'WHEEL_EXPENSE' AND t.created_at > :since GROUP BY t.player_id", nativeQuery = true)
    List<Object[]> getWheelExpensesPerPlayerSince(@Param("since") LocalDateTime since);
}
