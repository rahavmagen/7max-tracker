package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.ClubExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;

public interface ClubExpenseRepository extends JpaRepository<ClubExpense, Long> {
    List<ClubExpense> findAllByOrderByExpenseDateDescCreatedAtDesc();
    List<ClubExpense> findBySettledFalseOrderByExpenseDateDesc();
    List<ClubExpense> findBySettledTrueAndPaidByOrderByExpenseDateDesc(ClubExpense.PaidBy paidBy);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ClubExpense e")
    BigDecimal sumAll();
}
