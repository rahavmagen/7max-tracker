package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByPlayerIdOrderByTransactionDateDesc(Long playerId);
    boolean existsBySourceRef(String sourceRef);
    List<Transaction> findBySourceRef(String sourceRef);

    @Query("SELECT t FROM Transaction t WHERE t.type IN :types AND t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<Transaction> findRecentByTypes(@Param("types") List<Transaction.Type> types, @Param("since") LocalDateTime since);

    List<Transaction> findByPendingConfirmationTrueOrderByCreatedAtDesc();
}
