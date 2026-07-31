package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.ClubExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ClubExpenseRepository extends JpaRepository<ClubExpense, Long> {
    List<ClubExpense> findAllByOrderByExpenseDateDescCreatedAtDesc();
    List<ClubExpense> findBySettledFalseOrderByExpenseDateDesc();
    List<ClubExpense> findBySettledTrueAndPaidByOrderByExpenseDateDesc(ClubExpense.PaidBy paidBy);
    List<ClubExpense> findBySettledTrue();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ClubExpense e")
    BigDecimal sumAll();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ClubExpense e WHERE e.settled = true AND e.paidBy = 'ADMIN'")
    BigDecimal sumSettledAdminClub();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ClubExpense e WHERE e.settled = true AND e.paidBy = 'CLUB'")
    BigDecimal sumSettledClubPaid();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ClubExpense e WHERE e.settled = true AND e.expenseDate >= :from AND e.expenseDate <= :to")
    BigDecimal sumSettledBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
