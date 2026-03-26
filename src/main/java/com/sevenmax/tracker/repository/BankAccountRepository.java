package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {
    List<BankAccount> findByActiveTrueOrderByNameAsc();
}
