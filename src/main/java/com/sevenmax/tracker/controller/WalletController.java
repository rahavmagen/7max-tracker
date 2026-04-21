package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.AdminWalletStartingBalance;
import com.sevenmax.tracker.entity.BankAccount;
import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.repository.AdminWalletStartingBalanceRepository;
import com.sevenmax.tracker.repository.BankAccountRepository;
import com.sevenmax.tracker.repository.BankTransactionRepository;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WalletController {

    private final WalletService walletService;
    private final BankAccountRepository bankAccountRepository;
    private final ImportSummaryRepository importSummaryRepository;
    private final AdminWalletStartingBalanceRepository startingBalanceRepository;
    private final BankTransactionRepository bankTransactionRepository;

    @Value("${app.wallets.starting-balance-editable:false}")
    private boolean startingBalanceEditable;

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();

        List<Map<String, Object>> adminWallets = walletService.getAdminSummaries();
        BigDecimal adminTotal = adminWallets.stream()
            .map(m -> (BigDecimal) m.get("balance"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal bankDeposits = bankTransactionRepository.sumAll();

        List<BankAccount> bankAccounts = bankAccountRepository.findAll();
        List<Map<String, Object>> bankWallets = new ArrayList<>();
        if (!bankAccounts.isEmpty()) {
            BankAccount ba = bankAccounts.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ba.getId());
            m.put("name", ba.getName());
            m.put("balance", bankDeposits);
            bankWallets.add(m);
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", null);
            m.put("name", "Bank");
            m.put("balance", bankDeposits);
            bankWallets.add(m);
        }

        BigDecimal clubTotal = adminTotal.add(bankDeposits);
        BigDecimal unassignedTotal = walletService.getUnassignedTotal();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adminWallets", adminWallets);
        response.put("bankAccounts", bankWallets);
        response.put("clubTotal", clubTotal);
        response.put("unassignedTotal", unassignedTotal);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String holder,
            Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(walletService.getHistory(from, to, holder));
    }

    @PostMapping("/starting-balance")
    public ResponseEntity<?> setStartingBalance(@RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        String adminUsername = (String) body.get("adminUsername");
        if (adminUsername == null || adminUsername.isBlank()) return ResponseEntity.badRequest().build();
        // Only allow setting if not already set (unless editable mode is on)
        if (!startingBalanceEditable && startingBalanceRepository.existsById(adminUsername)) {
            return ResponseEntity.status(409).body(Map.of("error", "Starting balance already set for " + adminUsername));
        }
        String notes = body.containsKey("notes") ? (String) body.get("notes") : null;
        AdminWalletStartingBalance sb = startingBalanceRepository.findById(adminUsername).orElse(new AdminWalletStartingBalance());
        sb.setAdminUsername(adminUsername);
        sb.setCashAmount(parseBD(body.get("cashAmount")));
        sb.setBitAmount(parseBD(body.get("bitAmount")));
        sb.setPayboxAmount(parseBD(body.get("payboxAmount")));
        sb.setKashcashAmount(parseBD(body.get("kashcashAmount")));
        sb.setOtherAmount(parseBD(body.get("otherAmount")));
        sb.setNotes(notes);
        startingBalanceRepository.save(sb);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private BigDecimal parseBD(Object v) {
        if (v == null || v.toString().isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }
}
