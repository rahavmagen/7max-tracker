package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.TicketAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface TicketAssetRepository extends JpaRepository<TicketAsset, Long> {

    List<TicketAsset> findAllByOrderByPurchaseDateDesc();

    @Query("SELECT COALESCE(SUM(t.quantityRemaining * t.faceValuePerTicket), 0) FROM TicketAsset t WHERE t.quantityRemaining > 0")
    BigDecimal sumRemainingFaceValue();
}
