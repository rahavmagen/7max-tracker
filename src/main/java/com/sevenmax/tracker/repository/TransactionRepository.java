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

    @Query("SELECT t FROM Transaction t WHERE t.createdAt >= :from AND t.createdAt < :to ORDER BY t.createdAt DESC")
    List<Transaction> findAllBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<Transaction> findByPendingConfirmationTrueOrderByCreatedAtDesc();
    List<Transaction> findByPlayerIdAndPendingConfirmationTrue(Long playerId);
    Optional<Transaction> findFirstByPlayerIdAndAmountAndPendingConfirmationTrue(Long playerId, BigDecimal amount);
    Optional<Transaction> findFirstByPlayerIdAndAmountAndTypeAndPendingConfirmationTrue(Long playerId, BigDecimal amount, Transaction.Type type);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND (t.source_ref IS NULL OR t.source_ref NOT LIKE 'SCREEN:%') AND t.created_at > :since", nativeQuery = true)
    BigDecimal sumDepositsSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND t.source_ref = 'SCREEN:CREDIT' AND t.created_at > :since", nativeQuery = true)
    BigDecimal sumCreditsSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'WHEEL_EXPENSE' AND t.created_at > :since", nativeQuery = true)
    BigDecimal sumWheelExpensesSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'WHEEL_EXPENSE'", nativeQuery = true)
    BigDecimal sumAllWheelExpenses();

    @Query("SELECT t FROM Transaction t WHERE t.type = com.sevenmax.tracker.entity.Transaction.Type.WHEEL_EXPENSE")
    List<Transaction> findAllWheelExpenses();

    @Query(value = "SELECT t.player_id, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND (t.source_ref IS NULL OR t.source_ref NOT LIKE 'SCREEN:%') AND t.created_at > :since GROUP BY t.player_id", nativeQuery = true)
    List<Object[]> getDepositsPerPlayerSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT t.player_id, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND t.source_ref = 'SCREEN:CREDIT' AND t.created_at > :since GROUP BY t.player_id", nativeQuery = true)
    List<Object[]> getCreditsPerPlayerSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT t.player_id, COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'WHEEL_EXPENSE' AND t.created_at > :since GROUP BY t.player_id", nativeQuery = true)
    List<Object[]> getWheelExpensesPerPlayerSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND (t.source_ref IS NULL OR t.source_ref NOT LIKE 'SCREEN:%')", nativeQuery = true)
    BigDecimal sumAllBankDeposits();

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND t.source_ref = 'SCREEN:CREDIT'", nativeQuery = true)
    BigDecimal sumAllCreditsGiven();

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'WITHDRAWAL' AND t.source_ref = 'SCREEN:CREDIT'", nativeQuery = true)
    BigDecimal sumAllCreditWithdrawals();

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND (t.source_ref IS NULL OR t.source_ref NOT LIKE 'SCREEN:%') AND t.created_at >= :from AND t.created_at < :to", nativeQuery = true)
    BigDecimal sumBankDepositsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'DEPOSIT' AND t.source_ref = 'SCREEN:CREDIT' AND t.created_at >= :from AND t.created_at < :to", nativeQuery = true)
    BigDecimal sumCreditsGivenBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = 'WITHDRAWAL' AND t.source_ref = 'SCREEN:CREDIT' AND t.created_at >= :from AND t.created_at < :to", nativeQuery = true)
    BigDecimal sumCreditWithdrawalsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t WHERE t.type = :type", nativeQuery = true)
    BigDecimal sumByTypeName(@Param("type") String type);

    @Query("SELECT t FROM Transaction t WHERE t.type IN :types ORDER BY t.createdAt DESC")
    List<Transaction> findByTypeIn(@Param("types") List<Transaction.Type> types);
}
