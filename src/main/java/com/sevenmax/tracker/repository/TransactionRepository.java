package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByPlayerIdOrderByTransactionDateDesc(Long playerId);
    boolean existsBySourceRef(String sourceRef);
}
