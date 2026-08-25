package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.service.LiveTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/live-tickets")
@RequiredArgsConstructor
public class LiveTicketController {

    private final LiveTicketService liveTicketService;

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }

    /** Outstanding (unused) live tickets owed to agent-side sat-to-live winners. */
    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(liveTicketService.listUnused());
    }

    /** Full history — every live ticket (used and unused). */
    @GetMapping("/history")
    public ResponseEntity<?> history(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(liveTicketService.listAll());
    }

    /** Mark a ticket used (logs an informational "used" transaction). */
    @PostMapping("/{id}/use")
    public ResponseEntity<?> use(@PathVariable Long id, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        try {
            liveTicketService.use(id, auth != null ? auth.getName() : null);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Re-scan sat-to-live games and create any missing tickets. */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(Map.of("created", liveTicketService.syncAll()));
    }
}
