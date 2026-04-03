package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XlsMatchingUnitTest {

    private final XlsMatchingService service = new XlsMatchingService();

    private Transaction tx(Transaction.Type type, int amount) {
        Player p = new Player();
        p.setUsername("test");
        Transaction t = new Transaction();
        t.setPlayer(p);
        t.setType(type);
        t.setAmount(BigDecimal.valueOf(amount));
        return t;
    }

    @Test
    void scenario1_addCredit_matchesSendChips() {
        // Add Credit +1000: DEPOSIT, chip delta = +1000, XLS Send Chips +1000, xls_net = +1000
        assertThat(service.isGroupMatch(
                List.of(tx(Transaction.Type.DEPOSIT, 1000)),
                BigDecimal.valueOf(1000)
        )).isTrue();
    }

    @Test
    void scenario2_removeCredit_matchesClaimChips() {
        // Remove Credit -1000: WITHDRAWAL, chip delta = -1000, XLS Claim Chips +1000, xls_net = -1000
        assertThat(service.isGroupMatch(
                List.of(tx(Transaction.Type.WITHDRAWAL, 1000)),
                BigDecimal.valueOf(-1000)
        )).isTrue();
    }

    @Test
    void scenario4_transfer_payerSide() {
        // Transfer A→B: payer A gets REPAYMENT, XLS Send Chips +1000 to A, xls_net = +1000
        assertThat(service.isGroupMatch(
                List.of(tx(Transaction.Type.REPAYMENT, 1000)),
                BigDecimal.valueOf(1000)
        )).isTrue();
    }

    @Test
    void scenario4_transfer_receiverSide() {
        // Transfer A→B: receiver B gets CREDIT, XLS Claim Chips +1000 from B, xls_net = -1000
        assertThat(service.isGroupMatch(
                List.of(tx(Transaction.Type.CREDIT, 1000)),
                BigDecimal.valueOf(-1000)
        )).isTrue();
    }

    @Test
    void scenario5_transfer_receiverReducedCreditNoChips() {
        // Transfer A→B, B uses noChipChange → B's WITHDRAWAL auto-confirmed, not pending
        // XLS: nothing for B → xls_net = 0
        // Pending is empty → isGroupMatch returns false (correct: no pending to confirm)
        assertThat(service.isGroupMatch(List.of(), BigDecimal.valueOf(0))).isFalse();
    }

    @Test
    void scenario8_promotion_matchesSendChips() {
        // Promotion +500: DEPOSIT, chip delta = +500, XLS Send Chips +500, xls_net = +500
        assertThat(service.isGroupMatch(
                List.of(tx(Transaction.Type.DEPOSIT, 500)),
                BigDecimal.valueOf(500)
        )).isTrue();
    }

    @Test
    void multipleActivities_summed() {
        // Player has two pending: Add Credit +1000 and Add Credit +500
        // XLS: Send Chips +1000 + Send Chips +500 → xls_net = +1500
        assertThat(service.isGroupMatch(
                List.of(tx(Transaction.Type.DEPOSIT, 1000), tx(Transaction.Type.DEPOSIT, 500)),
                BigDecimal.valueOf(1500)
        )).isTrue();
    }

    @Test
    void mismatch_doesNotMatch() {
        assertThat(service.isGroupMatch(
                List.of(tx(Transaction.Type.DEPOSIT, 1000)),
                BigDecimal.valueOf(500)
        )).isFalse();
    }

    @Test
    void emptyPending_neverMatches() {
        assertThat(service.isGroupMatch(List.of(), BigDecimal.valueOf(1000))).isFalse();
    }

    @Test
    void expectedChipDelta_mixedTypes() {
        // DEPOSIT 1000 → +1000, CREDIT 500 → -500, net = +500
        BigDecimal delta = service.expectedChipDelta(
                List.of(tx(Transaction.Type.DEPOSIT, 1000), tx(Transaction.Type.CREDIT, 500))
        );
        assertThat(delta).isEqualByComparingTo(BigDecimal.valueOf(500));
    }
}
