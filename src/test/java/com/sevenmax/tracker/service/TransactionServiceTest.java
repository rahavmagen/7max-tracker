package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock PlayerRepository playerRepository;

    TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, playerRepository);
    }

    private Player playerWithBalance(BigDecimal balance) {
        Player p = new Player();
        p.setBalance(balance);
        return p;
    }

    @Test
    void adminDepositCreditsPlayerBalance() {
        Player player = playerWithBalance(BigDecimal.valueOf(100));
        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.ADMIN_DEPOSIT);
        tx.setAmount(BigDecimal.valueOf(50));
        when(transactionRepository.save(tx)).thenReturn(tx);

        transactionService.addTransaction(tx);

        assertThat(player.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(150));
    }
}
