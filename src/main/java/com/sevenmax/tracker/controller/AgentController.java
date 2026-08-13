package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.AgentSettlement;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final UserRepository userRepository;

    /** Admin: list all agents with pending balances, plus games/rake over an optional date range */
    @GetMapping
    public ResponseEntity<?> getAllAgents(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        LocalDate fromDate, toDate;
        try {
            fromDate = from != null ? LocalDate.parse(from) : null;
            toDate   = to   != null ? LocalDate.parse(to)   : null;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format. Use ISO-8601 (yyyy-MM-dd)"));
        }
        return ResponseEntity.ok(agentService.getAllAgentsSummary(fromDate, toDate));
    }

    /** Agent or admin: pending balance + settlement history */
    @GetMapping("/{id}/summary")
    public ResponseEntity<?> getSummary(@PathVariable Long id, Authentication auth) {
        if (!isAdminOrOwner(auth, id)) return ResponseEntity.status(403).build();
        try {
            return ResponseEntity.ok(agentService.getAgentSummary(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** Agent or admin: game-by-game breakdown, optional ?from=&to= */
    @GetMapping("/{id}/breakdown")
    public ResponseEntity<?> getBreakdown(
            @PathVariable Long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication auth) {
        if (!isAdminOrOwner(auth, id)) return ResponseEntity.status(403).build();
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = from != null ? LocalDate.parse(from) : null;
            toDate   = to   != null ? LocalDate.parse(to)   : null;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format. Use ISO-8601 (yyyy-MM-dd)"));
        }
        try {
            return ResponseEntity.ok(agentService.getAgentBreakdown(id, fromDate, toDate));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin or agent: per-player rake stats with optional date filter */
    @GetMapping("/{id}/player-stats")
    public ResponseEntity<?> getPlayerStats(
            @PathVariable Long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication auth) {
        if (!isAdminOrOwner(auth, id)) return ResponseEntity.status(403).build();
        LocalDate fromDate, toDate;
        try {
            fromDate = from != null ? LocalDate.parse(from) : null;
            toDate   = to   != null ? LocalDate.parse(to)   : null;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format"));
        }
        try {
            return ResponseEntity.ok(agentService.getPlayerStats(id, fromDate, toDate));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin only: set rake percentage for an agent */
    @PatchMapping("/{id}/rake-percentage")
    public ResponseEntity<?> setRakePercentage(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            Object pct = body.get("rakePercentage");
            if (pct == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing rakePercentage"));
            agentService.setRakePercentage(id, new java.math.BigDecimal(pct.toString()));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin only: toggle whether the club manages this agent's players directly */
    @PatchMapping("/{id}/club-managed")
    public ResponseEntity<?> setClubManaged(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            Object v = body.get("clubManaged");
            boolean flag = v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
            agentService.setClubManaged(id, flag);
            return ResponseEntity.ok(Map.of("success", true, "clubManaged", flag));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin: total balance across all non-club-managed agents for a period (from defaults to last התחשבנות) */
    @GetMapping("/total-balance")
    public ResponseEntity<?> getTotalAgentBalance(
            @RequestParam(required = false) String from, @RequestParam(required = false) String to, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        LocalDate fromDate, toDate;
        try {
            fromDate = from != null && !from.isBlank() ? LocalDate.parse(from) : null;
            toDate   = to   != null && !to.isBlank()   ? LocalDate.parse(to)   : null;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format"));
        }
        return ResponseEntity.ok(agentService.getTotalAgentBalance(fromDate, toDate));
    }

    /** Admin: full transaction history across all agents (openings + payments) */
    @GetMapping("/ledger-history")
    public ResponseEntity<?> getLedgerHistory(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(agentService.getLedgerHistory());
    }

    /** Admin/agent: the system-wide תאריך התחשבנות אחרון, used as the page's default "from" */
    /** Mark/unmark an agent as handled this week (בוצע התחשבנות). Body: { value: true/false }. */
    @PatchMapping("/{id}/settled-week")
    public ResponseEntity<?> setSettledWeek(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        boolean value = body.get("value") == null || Boolean.parseBoolean(body.get("value").toString());
        agentService.setSettledThisWeek(id, value);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "settledThisWeek", value));
    }

    /** Clear the "settled this week" flag on all agents (uncheck all). */
    @PostMapping("/uncheck-settled-week")
    public ResponseEntity<?> uncheckAllSettledWeek(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        agentService.clearAllSettledThisWeek();
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @GetMapping("/last-settlement-date")
    public ResponseEntity<?> lastSettlementDate(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        LocalDate d = agentService.getLastSettlementDate();
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("date", d != null ? d.toString() : null);
        return ResponseEntity.ok(body);
    }

    /** Admin only: manually set the system-wide תאריך התחשבנות אחרון. Body: { date: "yyyy-MM-dd" } */
    @PutMapping("/last-settlement-date")
    public ResponseEntity<?> setLastSettlementDate(@RequestBody java.util.Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        if (body.get("date") == null) return ResponseEntity.badRequest().body(java.util.Map.of("error", "date is required"));
        LocalDate date = LocalDate.parse(body.get("date").toString());
        agentService.setLastSettlementDate(date, auth != null ? auth.getName() : null);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("date", date.toString());
        return ResponseEntity.ok(result);
    }

    /** Admin only: acknowledge ("Done") reconciliation flags for the given player ids */
    @PostMapping("/dismiss-flags")
    public ResponseEntity<?> dismissFlags(@RequestBody Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        Object ids = body.get("playerIds");
        if (!(ids instanceof List<?> list)) return ResponseEntity.badRequest().body(Map.of("error", "Missing playerIds"));
        List<Long> playerIds = list.stream().map(o -> Long.valueOf(o.toString())).collect(java.util.stream.Collectors.toList());
        int n = agentService.dismissFlags(playerIds);
        return ResponseEntity.ok(Map.of("success", true, "dismissed", n));
    }

    /** Admin or agent: balance breakdown over a date range (starting − agentRake − P&L + payments) */
    @GetMapping("/{id}/balance")
    public ResponseEntity<?> getBalance(@PathVariable Long id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            Authentication auth) {
        if (!isAdminOrOwner(auth, id)) return ResponseEntity.status(403).build();
        LocalDate fromDate, toDate;
        try {
            fromDate = from != null && !from.isBlank() ? LocalDate.parse(from) : null;
            toDate   = to   != null && !to.isBlank()   ? LocalDate.parse(to)   : null;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format"));
        }
        try {
            return ResponseEntity.ok(agentService.getAgentBalance(id, fromDate, toDate));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin or agent: ledger entries (openings + payments), newest first */
    @GetMapping("/{id}/ledger")
    public ResponseEntity<?> getLedger(@PathVariable Long id, Authentication auth) {
        if (!isAdminOrOwner(auth, id)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(agentService.getLedger(id));
    }

    /** Admin only: set/re-set the opening balance (baseline) for an agent */
    @PostMapping("/{id}/ledger/opening")
    public ResponseEntity<?> addOpening(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        return addLedger(id, com.sevenmax.tracker.entity.AgentLedgerEntry.Type.OPENING, body, auth);
    }

    /** Admin only: log a payment (signed: + = we paid the agent, − = the agent paid us) */
    @PostMapping("/{id}/ledger/payment")
    public ResponseEntity<?> addPayment(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        return addLedger(id, com.sevenmax.tracker.entity.AgentLedgerEntry.Type.PAYMENT, body, auth);
    }

    private ResponseEntity<?> addLedger(Long id, com.sevenmax.tracker.entity.AgentLedgerEntry.Type type,
                                        Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            Object amt = body.get("amount");
            if (amt == null) return ResponseEntity.badRequest().body(Map.of("error", "amount is required"));
            java.math.BigDecimal amount = new java.math.BigDecimal(amt.toString());
            LocalDate date = body.get("effectiveDate") != null && !body.get("effectiveDate").toString().isBlank()
                ? LocalDate.parse(body.get("effectiveDate").toString()) : LocalDate.now();
            String notes = body.get("notes") != null ? body.get("notes").toString() : null;
            agentService.addLedgerEntry(id, type, amount, date, notes, auth != null ? auth.getName() : "system");
            LocalDate fromDate = body.get("periodFrom") != null && !body.get("periodFrom").toString().isBlank()
                ? LocalDate.parse(body.get("periodFrom").toString()) : null;
            LocalDate toDate = body.get("periodTo") != null && !body.get("periodTo").toString().isBlank()
                ? LocalDate.parse(body.get("periodTo").toString()) : null;
            return ResponseEntity.ok(agentService.getAgentBalance(id, fromDate, toDate));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin only: delete a ledger entry (fix a mistake) */
    @DeleteMapping("/ledger/{entryId}")
    public ResponseEntity<?> deleteLedgerEntry(@PathVariable Long entryId, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        agentService.deleteLedgerEntry(entryId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Admin only: trigger settlement. Optional body { amount } overrides the computed agent
     *  share (e.g. corrected in the settle popup) as the amount recorded as the expense. */
    @PostMapping("/{id}/settle")
    public ResponseEntity<?> settle(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            java.math.BigDecimal overrideAmount = (body != null && body.get("amount") != null)
                ? new java.math.BigDecimal(body.get("amount").toString()) : null;
            AgentSettlement settlement = agentService.settleAgent(id, overrideAmount);
            return ResponseEntity.ok(Map.of(
                "settlementId", settlement.getId(),
                "agentShare", settlement.getAgentShare(),
                "fromDate", settlement.getFromDate().toString(),
                "toDate", settlement.getToDate().toString()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin only: manually record an agent-rake expense for an arbitrary amount, not tied to
     *  unsettled game results (e.g. a correction). Body: { amount, notes } */
    @PostMapping("/{id}/rake-expense")
    public ResponseEntity<?> addRakeExpense(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            Object amt = body.get("amount");
            if (amt == null) return ResponseEntity.badRequest().body(Map.of("error", "amount is required"));
            java.math.BigDecimal amount = new java.math.BigDecimal(amt.toString());
            if (amount.signum() <= 0) return ResponseEntity.badRequest().body(Map.of("error", "Amount must be positive"));
            String notes = body.get("notes") != null ? body.get("notes").toString() : null;
            var expense = agentService.addManualRakeExpense(id, amount, notes, auth != null ? auth.getName() : "system");
            return ResponseEntity.ok(Map.of("id", expense.getId(), "amount", expense.getAmount()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isAdminOrOwner(Authentication auth, Long agentId) {
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null) return false;
        if (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MANAGER) return true;
        return user.getPlayer() != null && agentId.equals(user.getPlayer().getId());
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        return user != null && (user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.MANAGER);
    }
}
