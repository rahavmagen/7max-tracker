package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.PlayerTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
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

}
