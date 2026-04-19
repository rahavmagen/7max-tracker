package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.AdminExpense;
import com.sevenmax.tracker.entity.ClubExpense;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.PlayerTransfer;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.AdminExpenseRepository;
import com.sevenmax.tracker.repository.BankAccountRepository;
import com.sevenmax.tracker.repository.ClubExpenseRepository;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
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
    private final ImportSummaryRepository importSummaryRepository;
    private final PlayerRepository playerRepository;
    private final PlayerTransferRepository playerTransferRepository;
    private final BankAccountRepository bankAccountRepository;

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

        // Single unified paid list (no VAT split)
        List<Map<String, Object>> paid = new ArrayList<>();

        all.stream()
            .filter(e -> Boolean.TRUE.equals(e.getSettled()))
            .forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("entityType", "ADMIN_EXPENSE");
                m.put("who", e.getAdminUsername());
                m.put("amount", e.getAmount());
                m.put("notes", e.getNotes());
                m.put("expenseDate", e.getExpenseDate() != null ? e.getExpenseDate().toString() : null);
                m.put("settledAt", e.getSettledAt() != null ? e.getSettledAt().toString() : null);
                m.put("settledBy", e.getSettledBy());
                m.put("paidFromAdminUsername", e.getPaidFromAdminUsername());
                paid.add(m);
            });

        clubExpenseRepository.findBySettledTrue().forEach(ce -> {
            String name = ce.getPaidBy() == ClubExpense.PaidBy.ADMIN
                ? (ce.getAdminUser() != null ? ce.getAdminUser() : "Admin")
                : ("🏦 " + (ce.getBankAccount() != null ? ce.getBankAccount().getName() : "Club"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ce.getId());
            m.put("entityType", "CLUB_EXPENSE");
            m.put("who", name);
            m.put("amount", ce.getAmount());
            m.put("notes", ce.getDescription());
            m.put("expenseDate", ce.getExpenseDate() != null ? ce.getExpenseDate().toString() : null);
            m.put("settledAt", ce.getSettledAt() != null ? ce.getSettledAt().toString() : null);
            m.put("settledBy", ce.getSettledBy());
            m.put("paidFromAdminUsername", ce.getPaidFromAdminUsername());
            paid.add(m);
        });

        paid.sort(Comparator.comparing(m -> m.get("expenseDate") != null ? m.get("expenseDate").toString() : ""));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("admins", result);
        response.put("grandTotal", grandTotal);
        response.put("paid", paid);
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
            if (expense.getAmount() != null) {
                deductFromBank(expense.getAmount());
                createBankHistoryEntry(expense, auth);
            }
            return ResponseEntity.ok(expenseRepository.save(expense));
        }).orElse(ResponseEntity.notFound().build());
    }

    // PATCH /admin-expenses/{id}/pay — pay an expense, recording which wallet it came from
    @PatchMapping("/{id}/pay")
    public ResponseEntity<?> pay(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        return expenseRepository.findById(id).map(expense -> {
            expense.setSettled(true);
            expense.setSettledBy(auth != null ? auth.getName() : "system");
            expense.setSettledAt(LocalDate.now());
            if (body.get("paidFromAdminUsername") != null) {
                expense.setPaidFromAdminUsername(body.get("paidFromAdminUsername").toString());
            }
            if (body.get("paidFromBankAccountId") instanceof Number n) {
                expense.setPaidFromBankAccountId(n.longValue());
            }
            if (expense.getAmount() != null) {
                deductFromBank(expense.getAmount());
                createBankHistoryEntry(expense, auth);
            }
            return ResponseEntity.ok(expenseRepository.save(expense));
        }).orElse(ResponseEntity.notFound().build());
    }

    private void createBankHistoryEntry(AdminExpense expense, Authentication auth) {
        PlayerTransfer transfer = new PlayerTransfer();
        transfer.setAmount(expense.getAmount());
        transfer.setMethod(Transaction.Method.CASH);
        transfer.setNotes("Expense payment to " + expense.getAdminUsername()
                + (expense.getNotes() != null ? ": " + expense.getNotes() : ""));
        transfer.setTransferDate(expense.getSettledAt() != null ? expense.getSettledAt() : java.time.LocalDate.now());
        transfer.setCreatedByUsername(auth != null ? auth.getName() : "system");
        transfer.setConfirmed(true);

        // Try to link to admin's player record so history shows "CLUB → adminName"
        Player adminPlayer = playerRepository.findByUsername(expense.getAdminUsername()).orElse(null);
        if (adminPlayer != null) {
            transfer.setToPlayer(adminPlayer);
            // fromPlayer = null → CLUB
        } else {
            // Fallback: attach a bank account so query still picks it up
            bankAccountRepository.findAll().stream().findFirst()
                    .ifPresent(transfer::setFromBankAccount);
        }
        playerTransferRepository.save(transfer);
    }

    private void deductFromBank(BigDecimal amount) {
        ImportSummary summary = importSummaryRepository.findById(1L).orElse(new ImportSummary());
        summary.setId(1L);
        BigDecimal current = summary.getBankDeposits() != null ? summary.getBankDeposits() : BigDecimal.ZERO;
        summary.setBankDeposits(current.subtract(amount));
        summary.setLastUpdated(java.time.LocalDateTime.now());
        importSummaryRepository.save(summary);
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

}
