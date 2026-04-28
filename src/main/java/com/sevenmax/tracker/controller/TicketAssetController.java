package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import com.sevenmax.tracker.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/ticket-assets")
@RequiredArgsConstructor
public class TicketAssetController {

    private final TicketAssetRepository ticketAssetRepository;
    private final TicketGrantRepository ticketGrantRepository;
    private final AdminExpenseRepository adminExpenseRepository;
    private final PlayerRepository playerRepository;
    private final TransactionService transactionService;

    // GET /api/ticket-assets — list all ticket inventory rows with their grants
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAll() {
        List<TicketAsset> assets = ticketAssetRepository.findAllByOrderByPurchaseDateDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (TicketAsset asset : assets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", asset.getId());
            m.put("costPerTicket", asset.getCostPerTicket());
            m.put("faceValuePerTicket", asset.getFaceValuePerTicket());
            m.put("quantityTotal", asset.getQuantityTotal());
            m.put("quantityRemaining", asset.getQuantityRemaining());
            m.put("buyerAdminUsername", asset.getBuyerAdminUsername());
            m.put("purchaseDate", asset.getPurchaseDate());
            m.put("notes", asset.getNotes());
            m.put("createdAt", asset.getCreatedAt());

            List<TicketGrant> grants = ticketGrantRepository.findByAssetIdOrderByGrantedAtDesc(asset.getId());
            List<Map<String, Object>> grantList = new ArrayList<>();
            for (TicketGrant g : grants) {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("id", g.getId());
                gm.put("playerUsername", g.getPlayer().getUsername());
                gm.put("playerFullName", g.getPlayer().getFullName());
                gm.put("grantType", g.getGrantType());
                gm.put("status", g.getStatus());
                gm.put("grantedAt", g.getGrantedAt());
                gm.put("usedAt", g.getUsedAt());
                grantList.add(gm);
            }
            m.put("grants", grantList);
            result.add(m);
        }
        return ResponseEntity.ok(result);
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
    // body: { playerUsername, grantType: "CHIPS" | "LIVE" }
    @PostMapping("/{id}/grant")
    public ResponseEntity<?> grantTicket(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        TicketAsset asset = ticketAssetRepository.findById(id).orElseThrow();
        if (asset.getQuantityRemaining() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tickets remaining"));
        }
        if (body.get("playerUsername") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "playerUsername is required"));
        }
        String grantType = body.get("grantType") != null ? body.get("grantType").toString() : "CHIPS";
        if (!grantType.equals("CHIPS") && !grantType.equals("LIVE")) {
            return ResponseEntity.badRequest().body(Map.of("error", "grantType must be CHIPS or LIVE"));
        }

        String playerUsername = body.get("playerUsername").toString();
        Player player = playerRepository.findByUsername(playerUsername)
                .orElseThrow(() -> new RuntimeException("Player not found: " + playerUsername));

        asset.setQuantityRemaining(asset.getQuantityRemaining() - 1);
        ticketAssetRepository.save(asset);

        // Create grant record
        TicketGrant grant = new TicketGrant();
        grant.setAsset(asset);
        grant.setPlayer(player);
        grant.setGrantType(grantType);
        grant.setCreatedByUsername(auth != null ? auth.getName() : "system");

        if (grantType.equals("CHIPS")) {
            // Player receives chips equivalent to face value (positive transaction)
            grant.setStatus("USED");
            grant.setUsedAt(LocalDateTime.now());
            ticketGrantRepository.save(grant);

            Transaction tx = new Transaction();
            tx.setPlayer(player);
            tx.setType(Transaction.Type.TICKET_GRANT);
            tx.setAmount(asset.getFaceValuePerTicket()); // positive — adding chips
            tx.setNotes("Ticket grant (chips)");
            tx.setTransactionDate(LocalDate.now());
            tx.setCreatedByUsername(auth != null ? auth.getName() : "system");
            transactionService.addTransaction(tx);
        } else {
            // LIVE — just document, no chip transaction
            grant.setStatus("NOT_USED");
            ticketGrantRepository.save(grant);
        }

        return ResponseEntity.ok(Map.of("saved", true));
    }

    // POST /api/ticket-assets/grants/{grantId}/use — mark a LIVE grant as used
    @PostMapping("/grants/{grantId}/use")
    public ResponseEntity<?> markGrantUsed(@PathVariable Long grantId, Authentication auth) {
        TicketGrant grant = ticketGrantRepository.findById(grantId)
                .orElseThrow(() -> new RuntimeException("Grant not found: " + grantId));
        if (!"LIVE".equals(grant.getGrantType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only LIVE grants can be marked as used"));
        }
        if ("USED".equals(grant.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Already marked as used"));
        }
        grant.setStatus("USED");
        grant.setUsedAt(LocalDateTime.now());
        ticketGrantRepository.save(grant);
        return ResponseEntity.ok(Map.of("saved", true));
    }
}
