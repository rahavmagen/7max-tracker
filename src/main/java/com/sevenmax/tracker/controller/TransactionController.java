package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.TransactionRepository;
import com.sevenmax.tracker.service.PlayerService;
import com.sevenmax.tracker.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TransactionController {

    private final TransactionService transactionService;
    private final PlayerService playerService;
    private final TransactionRepository transactionRepository;

    @PostMapping
    public Transaction addTransaction(@RequestBody TransactionRequest req, org.springframework.security.core.Authentication auth) {
        Player player = playerService.getPlayer(req.playerId());
        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(req.type());
        tx.setAmount(req.amount());
        tx.setMethod(req.method());
        tx.setNotes(req.notes());
        tx.setTransactionDate(req.date() != null ? req.date() : LocalDate.now());
        tx.setCreatedByUsername(auth != null ? auth.getName() : null);
        tx.setPendingConfirmation(Boolean.TRUE.equals(req.pendingConfirmation()));
        if (req.sourceRef() != null) tx.setSourceRef(req.sourceRef());
        return transactionService.addTransaction(tx);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirmTransaction(@PathVariable Long id) {
        try {
            transactionService.confirmTransaction(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateTransaction(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            BigDecimal newAmount = new BigDecimal(body.get("amount").toString());
            String newNotes = body.get("notes") != null ? body.get("notes").toString() : null;
            Transaction updated = transactionService.updateTransaction(id, newAmount, newNotes);
            return ResponseEntity.ok(toDto(updated));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/recent")
    public List<Map<String, Object>> getRecent(@RequestParam(defaultValue = "30") int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Transaction.Type> types = List.of(Transaction.Type.CREDIT, Transaction.Type.DEPOSIT);
        return transactionRepository.findRecentByTypes(types, since).stream()
                .filter(tx -> tx.getSourceRef() == null || !tx.getSourceRef().startsWith("TRANSFER:"))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/range")
    public List<Map<String, Object>> getRange(
            @RequestParam String from,
            @RequestParam String to) {
        LocalDateTime fromDt = LocalDate.parse(from).atStartOfDay();
        LocalDateTime toDt = LocalDate.parse(to).plusDays(1).atStartOfDay();
        return transactionRepository.findAllBetween(fromDt, toDt).stream()
                .filter(tx -> tx.getSourceRef() == null || !tx.getSourceRef().startsWith("TRANSFER:"))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toDto(Transaction tx) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", tx.getId());
        m.put("playerId", tx.getPlayer().getId());
        m.put("playerUsername", tx.getPlayer().getUsername());
        m.put("playerFullName", tx.getPlayer().getFullName());
        m.put("type", tx.getType());
        m.put("amount", tx.getAmount());
        m.put("method", tx.getMethod());
        m.put("notes", tx.getNotes());
        m.put("transactionDate", tx.getTransactionDate() != null ? tx.getTransactionDate().toString() : null);
        m.put("sourceRef", tx.getSourceRef());
        return m;
    }

    record TransactionRequest(
            Long playerId,
            Transaction.Type type,
            BigDecimal amount,
            Transaction.Method method,
            String notes,
            LocalDate date,
            Boolean pendingConfirmation,
            String sourceRef
    ) {}
}
