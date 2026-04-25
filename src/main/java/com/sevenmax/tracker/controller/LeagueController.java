package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import com.sevenmax.tracker.service.LeagueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/league")
@RequiredArgsConstructor
public class LeagueController {

    private final LeagueConfigRepository leagueConfigRepository;
    private final LeagueSessionConfigRepository leagueSessionConfigRepository;
    private final GameSessionRepository gameSessionRepository;
    private final GameResultRepository gameResultRepository;
    private final LeagueService leagueService;

    private boolean isPlayer(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER"));
    }

    /** GET /api/league/sessions?gameType=NLH&dateFrom=2026-04-01&dateTo=2026-04-30 */
    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions(
            @RequestParam(required = false) String gameType,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();

        LocalDateTime from = dateFrom != null ? LocalDate.parse(dateFrom).atStartOfDay() : null;
        LocalDateTime to   = dateTo   != null ? LocalDate.parse(dateTo).plusDays(1).atStartOfDay() : null;

        // Build a map of sessionId → existing config for quick lookup
        Map<Long, LeagueSessionConfig> configMap = new HashMap<>();
        leagueSessionConfigRepository.findAll().forEach(c -> configMap.put(c.getSession().getId(), c));

        List<Map<String, Object>> result = new ArrayList<>();
        gameSessionRepository.findAll().stream()
            .filter(s -> s.getStartTime() != null)
            .filter(s -> gameType == null || gameType.isEmpty() || gameType.equals(s.getGameType() != null ? s.getGameType().name() : ""))
            .filter(s -> from == null || !s.getStartTime().isBefore(from))
            .filter(s -> to   == null || s.getStartTime().isBefore(to))
            .sorted((a, b) -> b.getStartTime().compareTo(a.getStartTime()))
            .forEach(s -> {
                LeagueSessionConfig cfg = configMap.get(s.getId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId", s.getId());
                m.put("tableName", s.getTableName());
                m.put("gameType", s.getGameType() != null ? s.getGameType().name() : null);
                m.put("startTime", s.getStartTime().toString());
                m.put("included", cfg != null && Boolean.TRUE.equals(cfg.getIncluded()));
                m.put("handsMultiplier", cfg != null && cfg.getHandsMultiplier() != null ? cfg.getHandsMultiplier() : 1);
                m.put("profitMultiplier", cfg != null && cfg.getProfitMultiplier() != null ? cfg.getProfitMultiplier() : 1);
                m.put("totalHands", gameResultRepository.sumHandsBySessionId(s.getId()));
                m.put("rake", gameResultRepository.sumRakeBySessionId(s.getId()));
                result.add(m);
            });
        return ResponseEntity.ok(result);
    }

    /** POST /api/league/config  body: { minHands, sessions: [{sessionId, included, handsMultiplier, profitMultiplier}] } */
    @PostMapping("/config")
    public ResponseEntity<?> saveConfig(@RequestBody Map<String, Object> body, Authentication auth) {
        if (isPlayer(auth)) return ResponseEntity.status(403).build();

        // Update minHands
        int minHands = body.containsKey("minHands") ? ((Number) body.get("minHands")).intValue() : 100;
        LeagueConfig cfg = leagueConfigRepository.findById(1L).orElseGet(() -> {
            LeagueConfig c = new LeagueConfig(); c.setId(1L); return c;
        });
        cfg.setMinHands(minHands);
        cfg.setUpdatedAt(LocalDateTime.now());
        leagueConfigRepository.save(cfg);

        // Update session configs
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) body.getOrDefault("sessions", List.of());
        for (Map<String, Object> s : sessions) {
            long sessionId = ((Number) s.get("sessionId")).longValue();
            boolean included = Boolean.TRUE.equals(s.get("included"));
            int hm = s.containsKey("handsMultiplier")  ? ((Number) s.get("handsMultiplier")).intValue()  : 1;
            int pm = s.containsKey("profitMultiplier") ? ((Number) s.get("profitMultiplier")).intValue() : 1;

            LeagueSessionConfig sc = leagueSessionConfigRepository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    LeagueSessionConfig c = new LeagueSessionConfig();
                    gameSessionRepository.findById(sessionId).ifPresent(c::setSession);
                    return c;
                });
            sc.setIncluded(included);
            sc.setHandsMultiplier(hm);
            sc.setProfitMultiplier(pm);
            sc.setUpdatedAt(LocalDateTime.now());
            if (sc.getSession() != null) leagueSessionConfigRepository.save(sc);
        }

        return ResponseEntity.ok(Map.of("saved", true));
    }

    /** GET /api/league/standings — any authenticated user */
    @GetMapping("/standings")
    public ResponseEntity<?> getStandings() {
        LeagueConfig cfg = leagueConfigRepository.findById(1L).orElseGet(() -> {
            LeagueConfig c = new LeagueConfig(); c.setMinHands(100); return c;
        });
        long sessionCount = leagueSessionConfigRepository.findByIncludedTrue().size();
        List<Map<String, Object>> standings = leagueService.computeStandings();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minHands", cfg.getMinHands());
        result.put("sessionCount", sessionCount);
        result.put("standings", standings);
        return ResponseEntity.ok(result);
    }
}
