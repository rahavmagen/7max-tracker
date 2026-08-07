package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.InactiveReportConfig;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.PlayerOutreach;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.InactiveReportConfigRepository;
import com.sevenmax.tracker.repository.PlayerOutreachRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Re-engagement CRM logic for inactive players: computes the cooldown-aware call-list, records
 * outreach ("mark handled"), and stores the weekly criteria. Shared by {@code ReportController}
 * and the weekly scheduler so both apply identical rules.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InactiveOutreachService {

    private final GameResultRepository gameResultRepository;
    private final PlayerOutreachRepository playerOutreachRepository;
    private final InactiveReportConfigRepository configRepository;
    private final PlayerRepository playerRepository;
    private final WhatsAppService whatsAppService;

    // Same numbers as the KashCash deposit alerts (NOTIFY_WHATSAPP env var).
    @Value("${app.kashcash.notification-whatsapp:}")
    private String notificationWhatsApp;

    @Value("${app.frontend-url:https://max7.vercel.app}")
    private String frontendUrl;

    /**
     * Inactive players for the given criteria, with players contacted within the cooldown removed.
     * Rows that resurfaced after a past contact carry their last-contacted info for display.
     */
    public List<Map<String, Object>> computeInactive(int recentDays, int lookbackDays, int minSessions,
                                                     String gameType, int cooldownDays) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recentStart = now.minusDays(recentDays);
        LocalDateTime refEnd = recentStart;
        LocalDateTime refStart = recentStart.minusDays(lookbackDays);
        String gt = (gameType == null || gameType.isBlank()) ? null : gameType;

        List<Object[]> rows = gameResultRepository.getInactivePlayers(refStart, refEnd, recentStart, minSessions, gt);

        List<Long> playerIds = rows.stream()
                .map(r -> ((Number) r[0]).longValue())
                .collect(Collectors.toList());

        // Latest outreach row per player (rows come newest-first, so first-seen wins).
        Map<Long, PlayerOutreach> latest = new HashMap<>();
        Map<Long, java.math.BigDecimal> balances = new HashMap<>();
        if (!playerIds.isEmpty()) {
            for (PlayerOutreach po : playerOutreachRepository.findByPlayerIdInOrderByHandledAtDesc(playerIds)) {
                latest.putIfAbsent(po.getPlayerId(), po);
            }
            for (Player p : playerRepository.findAllById(playerIds)) {
                balances.put(p.getId(), p.getBalance());
            }
        }

        LocalDateTime cooldownCutoff = now.minusDays(cooldownDays);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Long playerId = ((Number) r[0]).longValue();
            PlayerOutreach po = latest.get(playerId);
            // Hide players handled within the cooldown window.
            if (po != null && po.getHandledAt() != null && po.getHandledAt().isAfter(cooldownCutoff)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("playerId", playerId);
            m.put("username", r[1]);
            m.put("fullName", r[2]);
            m.put("sessionCount", r[3]);
            m.put("lastPlayed", r[4] != null ? r[4].toString() : null);
            m.put("balance", balances.get(playerId));
            m.put("lastHandledAt", po != null && po.getHandledAt() != null ? po.getHandledAt().toString() : null);
            m.put("lastHandledBy", po != null ? po.getHandledBy() : null);
            m.put("lastNote", po != null ? po.getNote() : null);
            result.add(m);
        }
        return result;
    }

    /** Record that an admin contacted a player (starts a fresh cooldown). */
    @Transactional
    public void handle(Long playerId, String note, String handledBy) {
        PlayerOutreach po = new PlayerOutreach();
        po.setPlayerId(playerId);
        po.setHandledAt(LocalDateTime.now());
        po.setHandledBy(handledBy);
        po.setNote(note != null && !note.isBlank() ? note.trim() : null);
        playerOutreachRepository.save(po);
    }

    /** The weekly criteria singleton (id = 1), created with defaults on first access. */
    public InactiveReportConfig getConfig() {
        return configRepository.findById(1L).orElseGet(InactiveReportConfig::new);
    }

    @Transactional
    public InactiveReportConfig saveConfig(int recentDays, int lookbackDays, int minSessions,
                                           String gameType, int cooldownDays, String updatedBy) {
        InactiveReportConfig c = configRepository.findById(1L).orElseGet(InactiveReportConfig::new);
        c.setId(1L);
        c.setRecentDays(recentDays);
        c.setLookbackDays(lookbackDays);
        c.setMinSessions(minSessions);
        c.setGameType(gameType == null || gameType.isBlank() ? null : gameType);
        c.setCooldownDays(cooldownDays);
        c.setUpdatedBy(updatedBy);
        c.setUpdatedAt(LocalDateTime.now());
        return configRepository.save(c);
    }

    /** Weekly job: compute with the saved criteria and WhatsApp the admins if anyone needs a call. */
    public void runWeekly() {
        InactiveReportConfig c = getConfig();
        List<Map<String, Object>> list = computeInactive(
                c.getRecentDays(), c.getLookbackDays(), c.getMinSessions(), c.getGameType(), c.getCooldownDays());
        if (list.isEmpty()) {
            log.info("Weekly inactive-players run: nobody to re-engage, no WhatsApp sent");
            return;
        }
        List<String> recipients = Arrays.stream((notificationWhatsApp == null ? "" : notificationWhatsApp).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (recipients.isEmpty()) {
            log.warn("Weekly inactive-players run: {} players but no WhatsApp recipients configured", list.size());
            return;
        }
        String msg = String.format(
                "📋 %d players to re-engage this week (silent %dd, ≥%d sessions). Open: %s/inactive-players",
                list.size(), c.getRecentDays(), c.getMinSessions(), frontendUrl);
        List<String> failed = whatsAppService.sendToAll(recipients, msg);
        log.info("Weekly inactive-players WhatsApp sent to {} recipient(s), {} failed", recipients.size(), failed.size());
    }
}
