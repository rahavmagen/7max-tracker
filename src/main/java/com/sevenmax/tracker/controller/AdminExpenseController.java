package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.AdminExpense;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.AdminExpenseRepository;
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
    private final UserRepository userRepository;

    // GET /admin-expenses — all expenses grouped by admin with totals
    @GetMapping
    public ResponseEntity<?> getAll() {
        List<AdminExpense> all = expenseRepository.findAll();

        // Group by adminUsername
        Map<String, List<AdminExpense>> grouped = all.stream()
            .collect(Collectors.groupingBy(e -> e.getAdminUsername() != null ? e.getAdminUsername() : "Unknown"));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<AdminExpense>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("adminUsername", entry.getKey());
            BigDecimal total = entry.getValue().stream()
                .map(AdminExpense::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            row.put("total", total);
            List<Map<String, Object>> entries = entry.getValue().stream()
                .sorted(Comparator.comparing(e -> e.getExpenseDate() != null ? e.getExpenseDate() : LocalDate.EPOCH))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("amount", e.getAmount());
                    m.put("notes", e.getNotes());
                    m.put("expenseDate", e.getExpenseDate() != null ? e.getExpenseDate().toString() : null);
                    m.put("createdBy", e.getCreatedBy());
                    m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
                    m.put("sourceRef", e.getSourceRef());
                    return m;
                })
                .collect(Collectors.toList());
            row.put("entries", entries);
            result.add(row);
        }

        // Sort by adminUsername
        result.sort(Comparator.comparing(m -> (String) m.get("adminUsername")));

        BigDecimal grandTotal = all.stream()
            .map(AdminExpense::getAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("admins", result);
        response.put("grandTotal", grandTotal);
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
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!expenseRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        expenseRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
