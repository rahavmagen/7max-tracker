package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.AdminExpense;
import com.sevenmax.tracker.entity.ClubExpense;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.AdminExpenseRepository;
import com.sevenmax.tracker.repository.ClubExpenseRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import com.sevenmax.tracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin-expenses")
@RequiredArgsConstructor
public class AdminExpenseController {

    private final AdminExpenseRepository expenseRepository;
    private final ClubExpenseRepository clubExpenseRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    // GET /admin-expenses — all expenses grouped by admin with totals
    @GetMapping
    public ResponseEntity<?> getAll() {
        List<AdminExpense> all = expenseRepository.findAll();

        // Group admin_expenses by adminUsername
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        all.stream()
            .sorted(Comparator.comparing(e -> e.getExpenseDate() != null ? e.getExpenseDate() : LocalDate.EPOCH))
            .forEach(e -> {
                String key = e.getAdminUsername() != null ? e.getAdminUsername() : "Unknown";
                grouped.computeIfAbsent(key, k -> new ArrayList<>());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("type", "ADMIN_EXPENSE");
                m.put("amount", e.getAmount());
                m.put("notes", e.getNotes());
                m.put("expenseDate", e.getExpenseDate() != null ? e.getExpenseDate().toString() : null);
                m.put("createdBy", e.getCreatedBy());
                m.put("sourceRef", e.getSourceRef());
                grouped.get(key).add(m);
            });

        // Merge unsettled club expenses (ADMIN case) into their admin's group
        List<ClubExpense> unsettledClub = clubExpenseRepository.findBySettledFalseOrderByExpenseDateDesc();
        unsettledClub.stream()
            .filter(ce -> ce.getPaidBy() == ClubExpense.PaidBy.ADMIN && ce.getAdminUser() != null)
            .forEach(ce -> {
                String key = ce.getAdminUser();
                grouped.computeIfAbsent(key, k -> new ArrayList<>());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ce.getId());
                m.put("type", "CLUB_EXPENSE");
                m.put("amount", ce.getAmount());
                m.put("notes", ce.getDescription());
                m.put("expenseDate", ce.getExpenseDate() != null ? ce.getExpenseDate().toString() : null);
                m.put("createdBy", ce.getCreatedBy());
                m.put("sourceRef", "CLUB_EXPENSE");
                grouped.get(key).add(m);
            });

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("adminUsername", entry.getKey());
            BigDecimal total = entry.getValue().stream()
                .map(m -> m.get("amount") instanceof BigDecimal ? (BigDecimal) m.get("amount") : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            row.put("total", total);
            row.put("entries", entry.getValue());
            result.add(row);
        }

        result.sort(Comparator.comparing(m -> (String) m.get("adminUsername")));

        BigDecimal grandTotal = all.stream()
            .map(AdminExpense::getAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Collect settled ADMIN club expenses as a separate list
        List<Map<String, Object>> paidClubExpenses = clubExpenseRepository
            .findBySettledTrueAndPaidByOrderByExpenseDateDesc(ClubExpense.PaidBy.ADMIN).stream()
            .filter(ce -> ce.getAdminUser() != null)
            .map(ce -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ce.getId());
                m.put("adminUser", ce.getAdminUser());
                m.put("amount", ce.getAmount());
                m.put("notes", ce.getDescription());
                m.put("expenseDate", ce.getExpenseDate() != null ? ce.getExpenseDate().toString() : null);
                m.put("settledAt", ce.getSettledAt() != null ? ce.getSettledAt().toString() : null);
                m.put("settledBy", ce.getSettledBy());
                return m;
            })
            .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("admins", result);
        response.put("grandTotal", grandTotal);
        response.put("paidClubExpenses", paidClubExpenses);
        return ResponseEntity.ok(response);
    }

    // GET /admin-expenses/admin-users — list of admin/manager users
    @GetMapping("/admin-users")
    public ResponseEntity<?> getAdminUsers() {
        List<Map<String, Object>> admins = userRepository.findAll().stream()
            .filter(u -> u.getRole() == User.Role.ADMIN || u.getRole() == User.Role.MANAGER)
            .filter(u -> Boolean.TRUE.equals(u.getActive()))
            .map(u -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username", u.getUsername());
                m.put("role", u.getRole().name());
                return m;
            })
            .sorted(Comparator.comparing(m -> (String) m.get("username")))
            .collect(Collectors.toList());
        return ResponseEntity.ok(admins);
    }

    // POST /admin-expenses — create manual expense
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        String adminUsername = (String) body.get("adminUsername");
        String amountStr = body.get("amount") != null ? body.get("amount").toString() : null;
        String notes = (String) body.get("notes");

        if (adminUsername == null || amountStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "adminUsername and amount are required"));
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount"));
        }

        AdminExpense expense = new AdminExpense();
        expense.setAdminUsername(adminUsername);
        expense.setAmount(amount);
        expense.setNotes(notes);
        expense.setExpenseDate(LocalDate.now());
        expense.setCreatedBy(auth != null ? auth.getName() : "system");

        return ResponseEntity.ok(expenseRepository.save(expense));
    }

    // PUT /admin-expenses/{id}
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return expenseRepository.findById(id).map(expense -> {
            if (body.get("amount") != null) {
                try {
                    expense.setAmount(new BigDecimal(body.get("amount").toString()));
                } catch (Exception ignored) {}
            }
            if (body.containsKey("notes")) {
                expense.setNotes((String) body.get("notes"));
            }
            return ResponseEntity.ok(expenseRepository.save(expense));
        }).orElse(ResponseEntity.notFound().build());
    }

    // DELETE /admin-expenses/{id}
    // For WHEEL expenses: also deletes the underlying WHEEL_EXPENSE transaction
    // so that backfillWheelExpenseAdminRecords() cannot recreate it on the next GG upload.
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return expenseRepository.findById(id).map(expense -> {
            String ref = expense.getSourceRef();
            if (ref != null && ref.startsWith("WHEEL:")) {
                // The transaction sourceRef is the part after "WHEEL:"
                String txRef = ref.substring("WHEEL:".length());
                transactionRepository.findBySourceRef(txRef).forEach(transactionRepository::delete);
            }
            expenseRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
