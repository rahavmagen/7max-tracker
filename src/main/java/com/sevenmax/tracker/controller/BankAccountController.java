package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.BankAccount;
import com.sevenmax.tracker.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bank-accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BankAccountController {

    private final BankAccountRepository bankAccountRepository;

    @GetMapping
    public List<BankAccount> getAll() {
        return bankAccountRepository.findByActiveTrueOrderByNameAsc();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            BankAccount account = new BankAccount();
            account.setName((String) body.get("name"));
            account.setAccountNumber((String) body.get("accountNumber"));
            account.setDescription((String) body.get("description"));
            return ResponseEntity.ok(bankAccountRepository.save(account));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return bankAccountRepository.findById(id).map(account -> {
            if (body.containsKey("name")) account.setName((String) body.get("name"));
            if (body.containsKey("accountNumber")) account.setAccountNumber((String) body.get("accountNumber"));
            if (body.containsKey("description")) account.setDescription((String) body.get("description"));
            if (body.containsKey("active")) account.setActive((Boolean) body.get("active"));
            return ResponseEntity.ok(bankAccountRepository.save(account));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return bankAccountRepository.findById(id).map(account -> {
            account.setActive(false);
            bankAccountRepository.save(account);
            return ResponseEntity.ok(Map.of("success", true));
        }).orElse(ResponseEntity.notFound().build());
    }
}
