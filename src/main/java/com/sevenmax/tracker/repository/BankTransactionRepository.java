package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {
    List<BankTransaction> findAllByOrderByTransactionDateDescCreatedAtDesc();

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM BankTransaction t")
    BigDecimal sumAll();
}
