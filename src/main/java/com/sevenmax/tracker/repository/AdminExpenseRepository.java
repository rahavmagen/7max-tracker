package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.AdminExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface AdminExpenseRepository extends JpaRepository<AdminExpense, Long> {
    List<AdminExpense> findByAdminUsernameOrderByExpenseDateDesc(String adminUsername);
    void deleteBySourceRef(String sourceRef);
    boolean existsBySourceRef(String sourceRef);

    @Query("SELECT DISTINCT e.adminUsername FROM AdminExpense e ORDER BY e.adminUsername")
    List<String> findDistinctAdminUsernames();
}
