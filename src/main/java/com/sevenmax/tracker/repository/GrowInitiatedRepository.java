package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.GrowInitiated;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface GrowInitiatedRepository extends JpaRepository<GrowInitiated, Long> {
    Optional<GrowInitiated> findByPaymentLinkProcessId(String paymentLinkProcessId);

    /**
     * Atomically claim this deposit for processing: sets processed=true only if it wasn't already.
     * Prevents double-crediting if Grow's callback is ever retried/duplicated.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GrowInitiated g SET g.processed = true WHERE g.id = :id AND (g.processed = false OR g.processed IS NULL)")
    int claimForProcessing(@Param("id") Long id);
}
