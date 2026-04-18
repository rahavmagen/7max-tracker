package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Transaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class XlsMatchingService {

    /**
     * Returns true if the XLS net chip delta matches the expected chip delta from the
     * pending transactions. xlsNet = sum(Send Chips positive) - sum(Claim Chips) for this player.
     * Empty pending never matches.
     */
    public boolean isGroupMatch(List<Transaction> pending, BigDecimal xlsNet) {
        if (pending.isEmpty()) return false;
        return expectedChipDelta(pending).compareTo(xlsNet) == 0;
    }

    /**
     * Computes expected net chip change from a list of pending transactions.
     * Each type has a known chip impact on the XLS Trade Record:
     *   DEPOSIT   → club sends chips to player       → +amount
     *   PAYMENT → payer got cash, club sends chips → +amount
     *   WITHDRAWAL → club claims chips from player   → -amount
     *   CREDIT    → receiver got cash, chips claimed → -amount
     *   WHEEL_EXPENSE → nightly cost, chips claimed  → -amount
     */
    public BigDecimal expectedChipDelta(List<Transaction> pending) {
        return pending.stream()
                .map(this::chipDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal chipDelta(Transaction tx) {
        return switch (tx.getType()) {
            case DEPOSIT       -> tx.getAmount();
            case PAYMENT       -> tx.getAmount();
            case WITHDRAWAL    -> tx.getAmount().negate();
            case CREDIT        -> tx.getAmount().negate();
            case WHEEL_EXPENSE -> tx.getAmount().negate();
            case CHIP_PROMO         -> tx.getAmount();   // club sends chips to player
            case PROMOTION          -> BigDecimal.ZERO;  // write-off: not XLS-matched
            case EXPENSE_REPAYMENT  -> tx.getAmount();   // club sends chips to admin as repayment
            case TICKET_GRANT       -> tx.getAmount().negate(); // face value deducted from player
        };
    }
}
