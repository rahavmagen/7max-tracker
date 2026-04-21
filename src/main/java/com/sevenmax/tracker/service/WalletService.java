package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.HashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final PlayerTransferRepository transferRepository;
    private final AdminExpenseRepository adminExpenseRepository;
    private final ClubExpenseRepository clubExpenseRepository;
    private final UserRepository userRepository;
    private final BankAccountRepository bankAccountRepository;
    private final AdminWalletStartingBalanceRepository startingBalanceRepository;

    public BigDecimal computeBalance(String adminUsername) {
        List<PlayerTransfer> transfers = transferRepository.findAll();
        List<AdminExpense> adminExpenses = adminExpenseRepository.findAll();
        List<ClubExpense> clubExpenses = clubExpenseRepository.findAll();

        BigDecimal balance = startingBalanceRepository.findById(adminUsername)
            .map(sb -> safe(sb.getStartingAmount()))
            .orElse(BigDecimal.ZERO);

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

    public BigDecimal getUnassignedTotal() {
        return transferRepository.findAll().stream()
            .filter(t -> t.getFromAdminUsername() == null && t.getToAdminUsername() == null)
            .filter(t -> {
                boolean fromClub = t.getFromPlayer() == null && t.getFromBankAccount() == null;
                boolean toClub = t.getToPlayer() == null && t.getToBankAccount() == null;
                return fromClub || toClub;
            })
            .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Map<String, Object>> getAdminSummaries() {
        List<String> adminUsernames = userRepository.findAll().stream()
            .filter(u -> u.getRole() == User.Role.ADMIN || u.getRole() == User.Role.MANAGER)
            .filter(u -> Boolean.TRUE.equals(u.getActive()))
            .map(User::getUsername)
            .sorted()
            .collect(Collectors.toList());

        List<PlayerTransfer> allTransfers = transferRepository.findAll();
        List<AdminExpense> allAdminExpenses = adminExpenseRepository.findAll();
        List<ClubExpense> allClubExpenses = clubExpenseRepository.findAll();

        List<AdminWalletStartingBalance> allStarting = startingBalanceRepository.findAll();
        Map<String, AdminWalletStartingBalance> startingMap = new HashMap<>();
        for (AdminWalletStartingBalance s : allStarting) startingMap.put(s.getAdminUsername(), s);

        List<Map<String, Object>> result = new ArrayList<>();
        for (String username : adminUsernames) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adminUsername", username);
            m.put("balance", computeBalance(username));
            AdminWalletStartingBalance sb = startingMap.get(username);
            BigDecimal sbTotal = sb != null ? safe(sb.getStartingAmount()) : null;
            m.put("startingBalance", sbTotal);
            m.put("startingBalanceNotes", sb != null ? sb.getNotes() : null);

            // Only STARTING needed — for opening-balance row in history
            Map<String, BigDecimal> breakdown = new LinkedHashMap<>();
            if (sb != null && safe(sb.getStartingAmount()).compareTo(BigDecimal.ZERO) != 0) {
                breakdown.put("STARTING", sb.getStartingAmount());
            }
            m.put("breakdown", breakdown);
            result.add(m);
        }
        return result;
    }

    public List<Map<String, Object>> getHistory(String from, String to, String holder) {
        List<Map<String, Object>> events = new ArrayList<>();
        LocalDate fromDate = from != null && !from.isEmpty() ? LocalDate.parse(from) : null;
        LocalDate toDate = to != null && !to.isEmpty() ? LocalDate.parse(to) : null;

        transferRepository.findAll().stream()
            .filter(t -> t.getFromAdminUsername() != null || t.getToAdminUsername() != null)
            .forEach(t -> {
                LocalDate d = t.getTransferDate();
                if (fromDate != null && d != null && d.isBefore(fromDate)) return;
                if (toDate != null && d != null && d.isAfter(toDate)) return;
                if (holder != null && !holder.isEmpty()) {
                    boolean matches = holder.equals(t.getFromAdminUsername())
                        || holder.equals(t.getToAdminUsername());
                    if (!matches) return;
                }
                events.add(buildTransferEvent(t, false));
            });

        transferRepository.findAll().stream()
            .filter(t -> t.getFromAdminUsername() == null && t.getToAdminUsername() == null)
            .filter(t -> {
                boolean fromClub = t.getFromPlayer() == null && t.getFromBankAccount() == null;
                boolean toClub = t.getToPlayer() == null && t.getToBankAccount() == null;
                return fromClub || toClub;
            })
            .forEach(t -> {
                LocalDate d = t.getTransferDate();
                if (fromDate != null && d != null && d.isBefore(fromDate)) return;
                if (toDate != null && d != null && d.isAfter(toDate)) return;
                if (holder != null && !holder.isEmpty()) return;
                events.add(buildTransferEvent(t, true));
            });

        adminExpenseRepository.findAll().stream()
            .filter(e -> Boolean.TRUE.equals(e.getSettled()) && e.getPaidFromAdminUsername() != null)
            .forEach(e -> {
                LocalDate d = e.getSettledAt();
                if (fromDate != null && d != null && d.isBefore(fromDate)) return;
                if (toDate != null && d != null && d.isAfter(toDate)) return;
                if (holder != null && !holder.isEmpty() && !holder.equals(e.getPaidFromAdminUsername())) return;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("type", "EXPENSE_PAID");
                m.put("transferDate", d != null ? d.toString() : null);
                m.put("fromAdminUsername", e.getPaidFromAdminUsername());
                m.put("toAdminUsername", null);
                m.put("fromPlayer", null);
                m.put("toPlayer", null);
                m.put("fromBankAccount", null);
                m.put("toBankAccount", null);
                m.put("amount", e.getAmount());
                m.put("notes", "Expense: " + (e.getNotes() != null ? e.getNotes() : e.getAdminUsername()));
                m.put("unassigned", false);
                events.add(m);
            });

        clubExpenseRepository.findAll().stream()
            .filter(e -> e.isSettled() && e.getPaidFromAdminUsername() != null)
            .forEach(e -> {
                LocalDate d = e.getSettledAt();
                if (fromDate != null && d != null && d.isBefore(fromDate)) return;
                if (toDate != null && d != null && d.isAfter(toDate)) return;
                if (holder != null && !holder.isEmpty() && !holder.equals(e.getPaidFromAdminUsername())) return;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("type", "EXPENSE_PAID");
                m.put("transferDate", d != null ? d.toString() : null);
                m.put("fromAdminUsername", e.getPaidFromAdminUsername());
                m.put("toAdminUsername", null);
                m.put("fromPlayer", null);
                m.put("toPlayer", null);
                m.put("fromBankAccount", null);
                m.put("toBankAccount", null);
                m.put("amount", e.getAmount());
                m.put("notes", "Club Expense: " + e.getDescription());
                m.put("unassigned", false);
                events.add(m);
            });

        events.sort(Comparator.comparing(
            (Map<String, Object> mm) -> mm.get("transferDate") != null ? mm.get("transferDate").toString() : ""
        ).reversed());

        return events;
    }

    private BigDecimal safe(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private Map<String, Object> buildTransferEvent(PlayerTransfer t, boolean unassigned) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("type", "TRANSFER");
        m.put("transferDate", t.getTransferDate() != null ? t.getTransferDate().toString() : null);
        m.put("fromAdminUsername", t.getFromAdminUsername());
        m.put("toAdminUsername", t.getToAdminUsername());
        m.put("fromPlayer", t.getFromPlayer() != null ? t.getFromPlayer().getUsername() : null);
        m.put("toPlayer", t.getToPlayer() != null ? t.getToPlayer().getUsername() : null);
        m.put("fromBankAccount", t.getFromBankAccount() != null ? t.getFromBankAccount().getName() : null);
        m.put("toBankAccount", t.getToBankAccount() != null ? t.getToBankAccount().getName() : null);
        m.put("amount", t.getAmount());
        m.put("method", t.getMethod() != null ? t.getMethod().name() : null);
        m.put("notes", t.getNotes());
        m.put("createdByUsername", t.getCreatedByUsername());
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        m.put("unassigned", unassigned);
        return m;
    }
}
