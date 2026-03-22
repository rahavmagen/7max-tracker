package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final PlayerRepository playerRepository;

    @Transactional
    public Transaction addTransaction(Transaction transaction) {
        Player player = transaction.getPlayer();

        // DEPOSIT and REPAYMENT (cashout) add to balance; everything else subtracts
        boolean isCredit = transaction.getType() == Transaction.Type.DEPOSIT
                || transaction.getType() == Transaction.Type.REPAYMENT;
        BigDecimal delta = isCredit ? transaction.getAmount() : transaction.getAmount().negate();

        player.setBalance(player.getBalance().add(delta));
        playerRepository.save(player);

        return transactionRepository.save(transaction);
    }
}
