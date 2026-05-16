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

    /** Admin: list all agents with pending balances */
    @GetMapping
    public ResponseEntity<?> getAllAgents(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(agentService.getAllAgentsSummary());
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

    /** Admin only: trigger settlement */
    @PostMapping("/{id}/settle")
    public ResponseEntity<?> settle(@PathVariable Long id, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            AgentSettlement settlement = agentService.settleAgent(id);
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
