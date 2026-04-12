package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.BankAccount;
import com.sevenmax.tracker.entity.ClubExpense;
import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.BankAccountRepository;
import com.sevenmax.tracker.repository.ClubExpenseRepository;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/club-expenses")
@RequiredArgsConstructor
public class ClubExpenseController {

    private final ClubExpenseRepository clubExpenseRepository;
    private final BankAccountRepository bankAccountRepository;
    private final ImportSummaryRepository importSummaryRepository;
    private final PlayerRepository playerRepository;
    private final TransactionService transactionService;

    @GetMapping
    public ResponseEntity<List<ClubExpense>> getAll() {
        return ResponseEntity.ok(clubExpenseRepository.findAllByOrderByExpenseDateDescCreatedAtDesc());
    }

    @GetMapping("/unsettled")
    public ResponseEntity<List<ClubExpense>> getUnsettled() {
        return ResponseEntity.ok(clubExpenseRepository.findBySettledFalseOrderByExpenseDateDesc());
    }

    @PostMapping
    public ResponseEntity<ClubExpense> create(@RequestBody Map<String, Object> body, Authentication auth) {
        ClubExpense e = new ClubExpense();
        e.setAmount(new BigDecimal(body.get("amount").toString()));
        e.setDescription(body.get("description").toString());
        e.setExpenseDate(LocalDate.parse(body.get("expenseDate").toString()));
        e.setPaidBy(ClubExpense.PaidBy.valueOf(body.get("paidBy").toString()));
        e.setCreatedBy(auth.getName());

        if (e.getPaidBy() == ClubExpense.PaidBy.ADMIN) {
            e.setAdminUser(body.get("adminUser").toString());
            e.setSettled(false);
        } else {
            // CLUB case — already settled, deduct from bank balance
            BankAccount bank = null;
            if (body.get("bankAccountId") != null) {
                Long bankId = Long.valueOf(body.get("bankAccountId").toString());
                bank = bankAccountRepository.findById(bankId).orElse(null);
            }
            if (bank == null) {
                bank = bankAccountRepository.findAll().stream().findFirst().orElse(null);
            }
            e.setBankAccount(bank);
            e.setSettled(true);
            e.setSettledAt(e.getExpenseDate());
            e.setSettledBy(auth.getName());
            deductFromBank(e.getAmount());
        }

        return ResponseEntity.ok(clubExpenseRepository.save(e));
    }

    @PatchMapping("/{id}/settle")
    public ResponseEntity<ClubExpense> settle(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        ClubExpense e = clubExpenseRepository.findById(id).orElseThrow();
        e.setSettled(true);
        e.setSettledAt(body.containsKey("settledAt") ? LocalDate.parse(body.get("settledAt").toString()) : LocalDate.now());
        e.setSettledBy(auth.getName());
        if (body.containsKey("bankAccountId")) {
            Long bankId = Long.valueOf(body.get("bankAccountId").toString());
            BankAccount bank = bankAccountRepository.findById(bankId).orElseThrow();
            e.setBankAccount(bank);
        }
        deductFromBank(e.getAmount());

        // If chips method: create a pending EXPENSE_REPAYMENT transaction for the admin player
        String method = body.containsKey("method") ? body.get("method").toString() : "CASH";
        if ("CHIPS".equals(method) && e.getAdminUser() != null) {
            playerRepository.findByUsername(e.getAdminUser()).ifPresent(player -> {
                Transaction tx = new Transaction();
                tx.setPlayer(player);
                tx.setType(Transaction.Type.EXPENSE_REPAYMENT);
                tx.setAmount(e.getAmount());
                tx.setNotes("Paid expense: " + e.getDescription());
                tx.setTransactionDate(e.getSettledAt());
                tx.setCreatedByUsername(auth.getName());
                tx.setPendingConfirmation(true);
                tx.setSourceRef("EXPENSE:" + e.getId());
                transactionService.addTransaction(tx);
            });
        }

        return ResponseEntity.ok(clubExpenseRepository.save(e));
    }

    private void deductFromBank(BigDecimal amount) {
        ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
        summary.setId(1L);
        BigDecimal current = summary.getBankDeposits() != null ? summary.getBankDeposits() : BigDecimal.ZERO;
        summary.setBankDeposits(current.subtract(amount));
        summary.setLastUpdated(java.time.LocalDateTime.now());
        importSummaryRepository.save(summary);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return clubExpenseRepository.findById(id).map(e -> {
            if (body.get("amount") != null) {
                try { e.setAmount(new BigDecimal(body.get("amount").toString())); } catch (Exception ignored) {}
            }
            if (body.containsKey("description")) {
                e.setDescription((String) body.get("description"));
            }
            return ResponseEntity.ok(clubExpenseRepository.save(e));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        clubExpenseRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }
}
