package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.PlayerTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PlayerTransferRepository extends JpaRepository<PlayerTransfer, Long> {
    List<PlayerTransfer> findByConfirmedFalseOrderByCreatedAtDesc();
    Optional<PlayerTransfer> findFirstByFromPlayerIdAndAmountAndConfirmedFalse(Long fromPlayerId, BigDecimal amount);
    Optional<PlayerTransfer> findFirstByToPlayerIdAndAmountAndConfirmedFalse(Long toPlayerId, BigDecimal amount);
    List<PlayerTransfer> findByFromPlayerIdOrToPlayerId(Long fromPlayerId, Long toPlayerId);
    List<PlayerTransfer> findByFromPlayerIdAndConfirmedFalse(Long fromPlayerId);
    List<PlayerTransfer> findByToPlayerIdAndConfirmedFalse(Long toPlayerId);
    Optional<PlayerTransfer> findFirstByFromPlayerIdAndAmountAndConfirmedTrue(Long fromPlayerId, BigDecimal amount);
    Optional<PlayerTransfer> findFirstByToPlayerIdAndAmountAndConfirmedTrue(Long toPlayerId, BigDecimal amount);
    List<PlayerTransfer> findByFromPlayerIdAndConfirmedTrue(Long fromPlayerId);

    @Query("SELECT t FROM PlayerTransfer t WHERE t.fromBankAccount IS NOT NULL OR t.toBankAccount IS NOT NULL OR (t.fromPlayer IS NULL AND t.toPlayer IS NOT NULL) OR (t.toPlayer IS NULL AND t.fromPlayer IS NOT NULL) ORDER BY t.transferDate ASC, t.createdAt ASC")
    List<PlayerTransfer> findBankRelatedTransfers();

}
