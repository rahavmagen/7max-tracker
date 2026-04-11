package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.BankAccount;
import com.sevenmax.tracker.entity.ClubExpense;
import com.sevenmax.tracker.repository.BankAccountRepository;
import com.sevenmax.tracker.repository.ClubExpenseRepository;
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
            // CLUB case — already settled
            Long bankId = Long.valueOf(body.get("bankAccountId").toString());
            BankAccount bank = bankAccountRepository.findById(bankId).orElseThrow();
            e.setBankAccount(bank);
            e.setSettled(true);
            e.setSettledAt(e.getExpenseDate());
            e.setSettledBy(auth.getName());
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
        return ResponseEntity.ok(clubExpenseRepository.save(e));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        clubExpenseRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }
}
