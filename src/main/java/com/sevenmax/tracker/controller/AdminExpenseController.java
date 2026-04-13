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

        // Group unsettled admin_expenses by adminUsername
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        all.stream()
            .filter(e -> !Boolean.TRUE.equals(e.getSettled()))
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
            .filter(e -> !Boolean.TRUE.equals(e.getSettled()))
            .map(AdminExpense::getAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Paid expenses split by vatType (NO_VAT / WITH_VAT)
        // Includes both settled AdminExpenses and settled ClubExpenses (ADMIN + CLUB paid)
        List<Map<String, Object>> paidNoVat = new ArrayList<>();
        List<Map<String, Object>> paidWithVat = new ArrayList<>();

        // Settled admin_expenses
        all.stream()
            .filter(e -> Boolean.TRUE.equals(e.getSettled()))
            .forEach(e -> {
                Map<String, Object> m = buildPaidEntry(e.getId(), "ADMIN_EXPENSE",
                    e.getAdminUsername(), e.getAmount(), e.getNotes(),
                    e.getExpenseDate(), e.getSettledAt(), e.getSettledBy(), e.getVatType());
                addToPaidList(m, e.getVatType(), paidNoVat, paidWithVat);
            });

        // Settled club expenses (both ADMIN and CLUB paidBy)
        clubExpenseRepository.findBySettledTrue().forEach(ce -> {
            String name = ce.getPaidBy() == ClubExpense.PaidBy.ADMIN
                ? (ce.getAdminUser() != null ? "👤 " + ce.getAdminUser() : "Admin")
                : ("🏦 " + (ce.getBankAccount() != null ? ce.getBankAccount().getName() : "Club"));
            Map<String, Object> m = buildPaidEntry(ce.getId(), "CLUB_EXPENSE",
                name, ce.getAmount(), ce.getDescription(),
                ce.getExpenseDate(), ce.getSettledAt(), ce.getSettledBy(), ce.getVatType());
            addToPaidList(m, ce.getVatType(), paidNoVat, paidWithVat);
        });

        // Sort by date
        Comparator<Map<String, Object>> byDate = Comparator.comparing(
            m -> m.get("expenseDate") != null ? m.get("expenseDate").toString() : "");
        paidNoVat.sort(byDate);
        paidWithVat.sort(byDate);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("admins", result);
        response.put("grandTotal", grandTotal);
        response.put("paidNoVat", paidNoVat);
        response.put("paidWithVat", paidWithVat);
        return ResponseEntity.ok(response);
    }

    // GET /admin-expenses/paid-totals — summary for TotalProfit page
    // Mirrors addToPaidList exactly: WITH_VAT → withVat, everything else (NO_VAT/null) → noVat
    // Includes ALL settled AdminExpenses + ALL settled ClubExpenses — same set as the paid sections in getAll()
    @GetMapping("/paid-totals")
    public ResponseEntity<?> getPaidTotals() {
        BigDecimal noVatTotal = BigDecimal.ZERO;
        BigDecimal withVatTotal = BigDecimal.ZERO;

        for (AdminExpense e : expenseRepository.findAll()) {
            if (!Boolean.TRUE.equals(e.getSettled())) continue;
            BigDecimal amt = e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO;
            if ("WITH_VAT".equals(e.getVatType())) withVatTotal = withVatTotal.add(amt);
            else noVatTotal = noVatTotal.add(amt);
        }

        for (ClubExpense ce : clubExpenseRepository.findBySettledTrue()) {
            BigDecimal amt = ce.getAmount() != null ? ce.getAmount() : BigDecimal.ZERO;
            if ("WITH_VAT".equals(ce.getVatType())) withVatTotal = withVatTotal.add(amt);
            else noVatTotal = noVatTotal.add(amt);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("noVatTotal", noVatTotal);
        response.put("withVatTotal", withVatTotal);
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

    // PATCH /admin-expenses/{id}/settle
    @PatchMapping("/{id}/settle")
    public ResponseEntity<?> settle(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        return expenseRepository.findById(id).map(expense -> {
            expense.setSettled(true);
            expense.setSettledBy(auth != null ? auth.getName() : "system");
            String settledAtStr = body.get("settledAt") != null ? body.get("settledAt").toString() : null;
            expense.setSettledAt(settledAtStr != null ? LocalDate.parse(settledAtStr) : LocalDate.now());
            if (body.get("vatType") != null) {
                expense.setVatType(body.get("vatType").toString());
            }
            return ResponseEntity.ok(expenseRepository.save(expense));
        }).orElse(ResponseEntity.notFound().build());
    }

    // PATCH /admin-expenses/{id}/vat-type — move between NO_VAT and WITH_VAT
    @PatchMapping("/{id}/vat-type")
    public ResponseEntity<?> setVatType(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return expenseRepository.findById(id).map(expense -> {
            expense.setVatType(body.get("vatType") != null ? body.get("vatType").toString() : null);
            return ResponseEntity.ok(expenseRepository.save(expense));
        }).orElse(ResponseEntity.notFound().build());
    }

    // DELETE /admin-expenses/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return expenseRepository.findById(id).map(expense -> {
            String ref = expense.getSourceRef();
            if (ref != null && ref.startsWith("WHEEL:")) {
                String txRef = ref.substring("WHEEL:".length());
                transactionRepository.findBySourceRef(txRef).forEach(transactionRepository::delete);
            }
            expenseRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> buildPaidEntry(Long id, String entityType, String who,
            BigDecimal amount, String notes, LocalDate expenseDate,
            LocalDate settledAt, String settledBy, String vatType) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("entityType", entityType);
        m.put("who", who);
        m.put("amount", amount);
        m.put("notes", notes);
        m.put("expenseDate", expenseDate != null ? expenseDate.toString() : null);
        m.put("settledAt", settledAt != null ? settledAt.toString() : null);
        m.put("settledBy", settledBy);
        m.put("vatType", vatType);
        return m;
    }

    private void addToPaidList(Map<String, Object> m, String vatType,
            List<Map<String, Object>> paidNoVat, List<Map<String, Object>> paidWithVat) {
        if ("WITH_VAT".equals(vatType)) paidWithVat.add(m);
        else paidNoVat.add(m); // null or NO_VAT → no-vat list
    }
}
