package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.AdminExpense;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.TicketAsset;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.AdminExpenseRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TicketAssetRepository;
import com.sevenmax.tracker.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ticket-assets")
@RequiredArgsConstructor
public class TicketAssetController {

    private final TicketAssetRepository ticketAssetRepository;
    private final AdminExpenseRepository adminExpenseRepository;
    private final PlayerRepository playerRepository;
    private final TransactionService transactionService;

    // GET /api/ticket-assets — list all ticket inventory rows
    @GetMapping
    public ResponseEntity<List<TicketAsset>> getAll() {
        return ResponseEntity.ok(ticketAssetRepository.findAllByOrderByPurchaseDateDesc());
    }

    // GET /api/ticket-assets/summary — total face value of remaining tickets
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        BigDecimal totalFaceValue = ticketAssetRepository.sumRemainingFaceValue();
        return ResponseEntity.ok(Map.of("totalFaceValue", totalFaceValue));
    }

    // POST /api/ticket-assets — buy tickets (creates inventory row + admin expense)
    @PostMapping
    public ResponseEntity<?> buyTickets(@RequestBody Map<String, Object> body, Authentication auth) {
        if (body.get("costPerTicket") == null || body.get("faceValuePerTicket") == null
                || body.get("quantity") == null || body.get("buyerAdminUsername") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "costPerTicket, faceValuePerTicket, quantity, and buyerAdminUsername are required"));
        }

        BigDecimal cost = new BigDecimal(body.get("costPerTicket").toString());
        BigDecimal faceValue = new BigDecimal(body.get("faceValuePerTicket").toString());
        int quantity = ((Number) body.get("quantity")).intValue();
        String buyer = body.get("buyerAdminUsername").toString();
        LocalDate purchaseDate = body.get("purchaseDate") != null
                ? LocalDate.parse(body.get("purchaseDate").toString())
                : LocalDate.now();
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;

        TicketAsset asset = new TicketAsset();
        asset.setCostPerTicket(cost);
        asset.setFaceValuePerTicket(faceValue);
        asset.setQuantityTotal(quantity);
        asset.setQuantityRemaining(quantity);
        asset.setBuyerAdminUsername(buyer);
        asset.setPurchaseDate(purchaseDate);
        asset.setNotes(notes);
        ticketAssetRepository.save(asset);

        // Auto-create admin expense for the buyer
        AdminExpense expense = new AdminExpense();
        expense.setAdminUsername(buyer);
        expense.setAmount(cost.multiply(BigDecimal.valueOf(quantity)));
        expense.setNotes("Ticket purchase: " + quantity + "x ₪" + faceValue + " tickets");
        expense.setExpenseDate(purchaseDate);
        expense.setCreatedBy(auth != null ? auth.getName() : "system");
        adminExpenseRepository.save(expense);

        return ResponseEntity.ok(asset);
    }

    // POST /api/ticket-assets/{id}/grant — grant one ticket to a player
    @PostMapping("/{id}/grant")
    public ResponseEntity<?> grantTicket(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        TicketAsset asset = ticketAssetRepository.findById(id).orElseThrow();
        if (asset.getQuantityRemaining() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tickets remaining"));
        }
        if (body.get("playerUsername") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "playerUsername is required"));
        }

        String playerUsername = body.get("playerUsername").toString();
        Player player = playerRepository.findByUsername(playerUsername)
                .orElseThrow(() -> new RuntimeException("Player not found: " + playerUsername));

        asset.setQuantityRemaining(asset.getQuantityRemaining() - 1);
        ticketAssetRepository.save(asset);

        // Create a TICKET_GRANT transaction: deduct face value from player
        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.TICKET_GRANT);
        tx.setAmount(asset.getFaceValuePerTicket().negate());
        tx.setNotes("Ticket grant");
        tx.setTransactionDate(LocalDate.now());
        tx.setCreatedByUsername(auth != null ? auth.getName() : "system");
        transactionService.addTransaction(tx);

        return ResponseEntity.ok(asset);
    }
}
