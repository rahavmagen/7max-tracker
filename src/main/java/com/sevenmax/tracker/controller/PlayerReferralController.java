package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.PlayerReferral;
import com.sevenmax.tracker.repository.PlayerReferralRepository;
import com.sevenmax.tracker.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/referrals")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlayerReferralController {

    private final PlayerReferralRepository referralRepository;
    private final PlayerService playerService;

    @PostMapping
    public ResponseEntity<?> createReferral(@RequestBody ReferralRequest req, Authentication auth) {
        if (req.referrerPlayerId() == null || req.newPlayerName() == null || req.newPlayerName().isBlank()
                || req.newPlayerPhone() == null || req.newPlayerPhone().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "referrerPlayerId, newPlayerName and newPlayerPhone are required"));
        }
        Player referrer = playerService.getPlayer(req.referrerPlayerId());
        PlayerReferral r = new PlayerReferral();
        r.setReferrer(referrer);
        r.setNewPlayerName(req.newPlayerName().trim());
        r.setNewPlayerPhone(req.newPlayerPhone().trim());
        r.setDepositAmount(req.depositAmount());
        r.setCreatedByUsername(auth != null ? auth.getName() : null);
        return ResponseEntity.ok(toDto(referralRepository.save(r)));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getReferrals() {
        List<Map<String, Object>> result = referralRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteReferral(@PathVariable Long id, Authentication auth) {
        if (!referralRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        referralRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private Map<String, Object> toDto(PlayerReferral r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("referrerPlayerId", r.getReferrer().getId());
        m.put("referrerUsername", r.getReferrer().getUsername());
        m.put("referrerFullName", r.getReferrer().getFullName());
        m.put("newPlayerName", r.getNewPlayerName());
        m.put("newPlayerPhone", r.getNewPlayerPhone());
        m.put("depositAmount", r.getDepositAmount());
        m.put("createdByUsername", r.getCreatedByUsername());
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return m;
    }

    record ReferralRequest(
            Long referrerPlayerId,
            String newPlayerName,
            String newPlayerPhone,
            BigDecimal depositAmount
    ) {}
}
