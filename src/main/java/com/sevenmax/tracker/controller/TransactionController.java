package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.service.PlayerService;
import com.sevenmax.tracker.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TransactionController {

    private final TransactionService transactionService;
    private final PlayerService playerService;

    @PostMapping
    public Transaction addTransaction(@RequestBody TransactionRequest req, org.springframework.security.core.Authentication auth) {
        Player player = playerService.getPlayer(req.playerId());
        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(req.type());
        tx.setAmount(req.amount());
        tx.setMethod(req.method());
        tx.setNotes(req.notes());
        tx.setTransactionDate(req.date() != null ? req.date() : LocalDate.now());
        tx.setCreatedByUsername(auth != null ? auth.getName() : null);
        return transactionService.addTransaction(tx);
    }

    record TransactionRequest(
            Long playerId,
            Transaction.Type type,
            java.math.BigDecimal amount,
            Transaction.Method method,
            String notes,
            LocalDate date
    ) {}
}
