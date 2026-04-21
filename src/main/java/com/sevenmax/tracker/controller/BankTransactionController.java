package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.BankTransaction;
import com.sevenmax.tracker.repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/bank-transactions")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BankTransactionController {

    private final BankTransactionRepository repo;

    @GetMapping
    public ResponseEntity<?> getAll(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repo.findAllByOrderByTransactionDateDescCreatedAtDesc());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        Object amtVal = body.get("amount");
        if (amtVal == null) return ResponseEntity.badRequest().body(Map.of("error", "amount is required"));
        BankTransaction t = new BankTransaction();
        t.setAmount(new BigDecimal(amtVal.toString()));
        if (body.get("transactionDate") != null && !body.get("transactionDate").toString().isBlank())
            t.setTransactionDate(LocalDate.parse(body.get("transactionDate").toString()));
        if (body.get("notes") != null) t.setNotes(body.get("notes").toString());
        t.setCreatedBy(auth != null ? auth.getName() : null);
        return ResponseEntity.ok(repo.save(t));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }
}
