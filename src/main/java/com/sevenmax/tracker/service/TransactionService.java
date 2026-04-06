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

        // DEPOSIT and PAYMENT (cashout) add to balance; everything else subtracts
        boolean isCredit = transaction.getType() == Transaction.Type.DEPOSIT
                || transaction.getType() == Transaction.Type.PAYMENT;
        BigDecimal delta = isCredit ? transaction.getAmount() : transaction.getAmount().negate();

        player.setBalance(player.getBalance().add(delta));
        playerRepository.save(player);

        return transactionRepository.save(transaction);
    }

    @Transactional
    public Transaction updateTransaction(Long id, BigDecimal newAmount, String newNotes) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        BigDecimal diff = newAmount.subtract(tx.getAmount());
        boolean adds = tx.getType() == Transaction.Type.DEPOSIT || tx.getType() == Transaction.Type.PAYMENT;
        Player player = tx.getPlayer();
        player.setBalance(player.getBalance().add(adds ? diff : diff.negate()));
        playerRepository.save(player);
        tx.setAmount(newAmount);
        if (newNotes != null) tx.setNotes(newNotes);
        return transactionRepository.save(tx);
    }

    @Transactional
    public void confirmTransaction(Long id) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        tx.setPendingConfirmation(false);
        transactionRepository.save(tx);
    }
}
