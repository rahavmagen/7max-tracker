package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final PlayerTransferRepository transferRepository;
    private final AdminExpenseRepository adminExpenseRepository;
    private final ClubExpenseRepository clubExpenseRepository;
    private final UserRepository userRepository;
    private final BankAccountRepository bankAccountRepository;

    /** Compute how much club cash this admin is currently holding. */
    public BigDecimal computeBalance(String adminUsername) {
        List<PlayerTransfer> transfers = transferRepository.findAll();
        List<AdminExpense> adminExpenses = adminExpenseRepository.findAll();
        List<ClubExpense> clubExpenses = clubExpenseRepository.findAll();

        BigDecimal balance = BigDecimal.ZERO;

        for (PlayerTransfer t : transfers) {
            if (adminUsername.equals(t.getToAdminUsername())) {
                balance = balance.add(t.getAmount());
            }
            if (adminUsername.equals(t.getFromAdminUsername())) {
                balance = balance.subtract(t.getAmount());
            }
        }

        for (AdminExpense e : adminExpenses) {
            if (Boolean.TRUE.equals(e.getSettled())
                    && adminUsername.equals(e.getPaidFromAdminUsername())) {
                balance = balance.subtract(e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO);
            }
        }

        for (ClubExpense e : clubExpenses) {
            if (e.isSettled()
                    && adminUsername.equals(e.getPaidFromAdminUsername())) {
                balance = balance.subtract(e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO);
            }
        }

        return balance;
    }

    /** Summary: all admin usernames with their balance. */
    public List<Map<String, Object>> getAdminSummaries() {
        List<String> adminUsernames = userRepository.findAll().stream()
            .filter(u -> u.getRole() == User.Role.ADMIN || u.getRole() == User.Role.MANAGER)
            .filter(u -> Boolean.TRUE.equals(u.getActive()))
            .map(User::getUsername)
            .sorted()
            .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (String username : adminUsernames) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adminUsername", username);
            m.put("balance", computeBalance(username));
            result.add(m);
        }
        return result;
    }

    /** History: all wallet events (transfers with admin attribution + paid expenses). */
    public List<Map<String, Object>> getHistory() {
        List<Map<String, Object>> events = new ArrayList<>();

        // Transfers with any admin attribution
        transferRepository.findAll().stream()
            .filter(t -> t.getFromAdminUsername() != null || t.getToAdminUsername() != null)
            .forEach(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "TRANSFER");
                m.put("date", t.getTransferDate() != null ? t.getTransferDate().toString() : "");
                m.put("fromAdmin", t.getFromAdminUsername());
                m.put("toAdmin", t.getToAdminUsername());
                m.put("fromPlayer", t.getFromPlayer() != null ? t.getFromPlayer().getUsername() : null);
                m.put("toPlayer", t.getToPlayer() != null ? t.getToPlayer().getUsername() : null);
                m.put("amount", t.getAmount());
                m.put("method", t.getMethod() != null ? t.getMethod().toString() : "");
                m.put("notes", t.getNotes());
                m.put("createdBy", t.getCreatedByUsername());
                events.add(m);
            });

        // Paid admin expenses with admin payment source
        adminExpenseRepository.findAll().stream()
            .filter(e -> Boolean.TRUE.equals(e.getSettled()) && e.getPaidFromAdminUsername() != null)
            .forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "EXPENSE_PAID");
                m.put("date", e.getSettledAt() != null ? e.getSettledAt().toString() : "");
                m.put("fromAdmin", e.getPaidFromAdminUsername());
                m.put("toAdmin", null);
                m.put("description", "Expense: " + (e.getNotes() != null ? e.getNotes() : e.getAdminUsername()));
                m.put("amount", e.getAmount());
                m.put("notes", e.getNotes());
                events.add(m);
            });

        // Paid club expenses with admin payment source
        clubExpenseRepository.findAll().stream()
            .filter(e -> e.isSettled() && e.getPaidFromAdminUsername() != null)
            .forEach(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "EXPENSE_PAID");
                m.put("date", e.getSettledAt() != null ? e.getSettledAt().toString() : "");
                m.put("fromAdmin", e.getPaidFromAdminUsername());
                m.put("toAdmin", null);
                m.put("description", "Club Expense: " + e.getDescription());
                m.put("amount", e.getAmount());
                m.put("notes", e.getDescription());
                events.add(m);
            });

        // Sort newest first
        events.sort(Comparator.comparing(
            (Map<String, Object> m) -> m.get("date") != null ? m.get("date").toString() : ""
        ).reversed());

        return events;
    }
}
