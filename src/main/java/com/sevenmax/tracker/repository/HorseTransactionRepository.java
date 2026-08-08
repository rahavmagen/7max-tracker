package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.HorseTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HorseTransactionRepository extends JpaRepository<HorseTransaction, Long> {
    List<HorseTransaction> findByPlayerIdOrderByTransactionDateDesc(Long playerId);
}
