package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.ClubExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ClubExpenseRepository extends JpaRepository<ClubExpense, Long> {
    List<ClubExpense> findAllByOrderByExpenseDateDescCreatedAtDesc();
    List<ClubExpense> findBySettledFalseOrderByExpenseDateDesc();
}
