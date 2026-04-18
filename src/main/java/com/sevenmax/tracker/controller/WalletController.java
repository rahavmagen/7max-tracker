package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.BankAccount;
import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.repository.BankAccountRepository;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.service.WalletService;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();

        List<Map<String, Object>> adminWallets = walletService.getAdminSummaries();
        BigDecimal adminTotal = adminWallets.stream()
            .map(m -> (BigDecimal) m.get("balance"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        ImportSummary summary = importSummaryRepository.findById(1L).orElse(null);
        BigDecimal bankDeposits = summary != null && summary.getBankDeposits() != null
            ? summary.getBankDeposits() : BigDecimal.ZERO;

        List<BankAccount> bankAccounts = bankAccountRepository.findAll();
        List<Map<String, Object>> bankWallets = new ArrayList<>();
        if (!bankAccounts.isEmpty()) {
            BankAccount ba = bankAccounts.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ba.getId());
            m.put("name", ba.getName());
            m.put("balance", bankDeposits);
            bankWallets.add(m);
        } else if (bankDeposits.compareTo(BigDecimal.ZERO) != 0) {
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

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }
}
