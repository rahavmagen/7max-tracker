package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.KashcashInitiated;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface KashcashInitiatedRepository extends JpaRepository<KashcashInitiated, Long> {
    Optional<KashcashInitiated> findByKashcashTransactionId(String id);
}
