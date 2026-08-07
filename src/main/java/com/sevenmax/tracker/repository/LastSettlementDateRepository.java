package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.LastSettlementDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LastSettlementDateRepository extends JpaRepository<LastSettlementDate, Long> {
}
