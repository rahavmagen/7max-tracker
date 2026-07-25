package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.KashcashInitiated;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface KashcashInitiatedRepository extends JpaRepository<KashcashInitiated, Long> {
    Optional<KashcashInitiated> findByKashcashTransactionId(String id);

    /**
     * Atomically claim this deposit for processing: sets processed=true only if it wasn't already.
     * Returns 1 if this caller won the claim, 0 if another path (webhook vs frontend finalize) already did.
     * Closes the race that let a deposit be credited/notified twice.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE KashcashInitiated k SET k.processed = true WHERE k.id = :id AND (k.processed = false OR k.processed IS NULL)")
    int claimForProcessing(@Param("id") Long id);
}
