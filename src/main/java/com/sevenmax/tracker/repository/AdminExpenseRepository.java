package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.AdminExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface AdminExpenseRepository extends JpaRepository<AdminExpense, Long> {
    List<AdminExpense> findByAdminUsernameOrderByExpenseDateDesc(String adminUsername);
    void deleteBySourceRef(String sourceRef);
    boolean existsBySourceRef(String sourceRef);

    @Query("SELECT DISTINCT e.adminUsername FROM AdminExpense e ORDER BY e.adminUsername")
    List<String> findDistinctAdminUsernames();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM AdminExpense e WHERE e.adminUsername = :adminUsername")
    BigDecimal sumByAdminUsername(String adminUsername);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM AdminExpense e WHERE e.adminUsername != :adminUsername")
    BigDecimal sumExcludingAdminUsername(String adminUsername);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM AdminExpense e")
    BigDecimal sumAllExpenses();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM AdminExpense e WHERE e.expenseDate >= :from AND e.expenseDate <= :to")
    BigDecimal sumExpensesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM AdminExpense e WHERE e.adminUsername <> 'Wheel' AND (e.expenseType IS NULL OR e.expenseType <> 'AGENT') AND e.expenseDate >= :from AND e.expenseDate <= :to")
    BigDecimal sumGeneralExpensesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM AdminExpense e WHERE e.adminUsername = :adminUsername AND e.expenseDate >= :from AND e.expenseDate <= :to")
    BigDecimal sumByAdminUsernameBetween(@Param("adminUsername") String adminUsername, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM AdminExpense e WHERE e.expenseType = :expenseType AND e.expenseDate >= :from AND e.expenseDate <= :to")
    BigDecimal sumByExpenseTypeBetween(@Param("expenseType") String expenseType, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
