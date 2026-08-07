package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.InactiveReportConfig;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.PlayerAssignment;
import com.sevenmax.tracker.entity.PlayerOutreach;
import com.sevenmax.tracker.repository.GameResultRepository;
import com.sevenmax.tracker.repository.InactiveReportConfigRepository;
import com.sevenmax.tracker.repository.PlayerAssignmentRepository;
import com.sevenmax.tracker.repository.PlayerOutreachRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
    private final PlayerAssignmentRepository playerAssignmentRepository;
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
                                                     String gameType, int cooldownDays, String assignedTo) {
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
        Map<Long, String> phones = new HashMap<>();
        Map<Long, PlayerAssignment> assignments = new HashMap<>();
        if (!playerIds.isEmpty()) {
            for (PlayerOutreach po : playerOutreachRepository.findByPlayerIdInOrderByHandledAtDesc(playerIds)) {
                latest.putIfAbsent(po.getPlayerId(), po);
            }
            for (Player p : playerRepository.findAllById(playerIds)) {
                balances.put(p.getId(), p.getBalance());
                phones.put(p.getId(), p.getPhone());
            }
            for (PlayerAssignment pa : playerAssignmentRepository.findAllById(playerIds)) {
                assignments.put(pa.getPlayerId(), pa);
            }
        }

        String assignedToFilter = (assignedTo == null || assignedTo.isBlank()) ? null : assignedTo;
        LocalDateTime cooldownCutoff = now.minusDays(cooldownDays);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Long playerId = ((Number) r[0]).longValue();
            PlayerOutreach po = latest.get(playerId);
            // Hide players handled within the cooldown window.
            if (po != null && po.getHandledAt() != null && po.getHandledAt().isAfter(cooldownCutoff)) {
                continue;
            }
            PlayerAssignment pa = assignments.get(playerId);
            String assignedAdmin = pa != null ? pa.getAssignedAdminUsername() : null;
            if (assignedToFilter != null) {
                boolean wantsUnassigned = "UNASSIGNED".equals(assignedToFilter);
                if (wantsUnassigned && assignedAdmin != null) continue;
                if (!wantsUnassigned && !assignedToFilter.equals(assignedAdmin)) continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("playerId", playerId);
            m.put("username", r[1]);
            m.put("fullName", r[2]);
            m.put("sessionCount", r[3]);
            m.put("lastPlayed", r[4] != null ? r[4].toString() : null);
            m.put("balance", balances.get(playerId));
            m.put("phone", phones.get(playerId));
            m.put("lastHandledAt", po != null && po.getHandledAt() != null ? po.getHandledAt().toString() : null);
            m.put("lastHandledBy", po != null ? po.getHandledBy() : null);
            m.put("lastNote", po != null ? po.getNote() : null);
            m.put("assignedTo", assignedAdmin);
            result.add(m);
        }
        return result;
    }

    /** Assign (or clear, if adminUsername is null/blank) the re-engagement owner for a player. */
    @Transactional
    public void assign(Long playerId, String adminUsername, String assignedBy) {
        if (adminUsername == null || adminUsername.isBlank()) {
            if (playerAssignmentRepository.existsById(playerId)) {
                playerAssignmentRepository.deleteById(playerId);
            }
            return;
        }
        PlayerAssignment pa = playerAssignmentRepository.findById(playerId).orElseGet(PlayerAssignment::new);
        pa.setPlayerId(playerId);
        pa.setAssignedAdminUsername(adminUsername);
        pa.setAssignedAt(LocalDateTime.now());
        pa.setAssignedBy(assignedBy);
        playerAssignmentRepository.save(pa);
    }

    /** Full contact history across all players (not just currently-inactive ones), newest first,
     *  optionally bounded by date range - the audit-log view at the bottom of the report page. */
    public List<Map<String, Object>> getHistory(LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from != null ? from.atStartOfDay() : LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime toDt = to != null ? to.plusDays(1).atStartOfDay() : LocalDateTime.now().plusDays(1);
        List<PlayerOutreach> rows = playerOutreachRepository.findByHandledAtBetweenOrderByHandledAtDesc(fromDt, toDt);

        List<Long> playerIds = rows.stream().map(PlayerOutreach::getPlayerId).distinct().collect(Collectors.toList());
        Map<Long, Player> players = playerIds.isEmpty() ? Map.of()
                : playerRepository.findAllById(playerIds).stream()
                        .collect(Collectors.toMap(Player::getId, p -> p));

        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerOutreach po : rows) {
            Player p = players.get(po.getPlayerId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("playerId", po.getPlayerId());
            m.put("username", p != null ? p.getUsername() : null);
            m.put("fullName", p != null ? p.getFullName() : null);
            m.put("handledAt", po.getHandledAt() != null ? po.getHandledAt().toString() : null);
            m.put("handledBy", po.getHandledBy());
            m.put("note", po.getNote());
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
                c.getRecentDays(), c.getLookbackDays(), c.getMinSessions(), c.getGameType(), c.getCooldownDays(), null);
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
        String msg = buildNudgeMessage(list.size(), c);
        List<String> failed = whatsAppService.sendToAll(recipients, msg);
        log.info("Weekly inactive-players WhatsApp sent to {} recipient(s), {} failed", recipients.size(), failed.size());
    }

    private String buildNudgeMessage(int count, InactiveReportConfig c) {
        return String.format(
                "📋 יש %d שחקנים שצריך לעשות להם שימור, הם לא שיחקו במשך %d ימים.\nלצפייה: %s/inactive-players",
                count, c.getRecentDays(), frontendUrl);
    }

    /** Sends the real weekly-nudge message (using the saved criteria and current call-list) to a
     *  single phone number, ignoring the configured NOTIFY_WHATSAPP list entirely - for admins to
     *  verify the message/delivery before it goes out to everyone. Returns true if it sent OK. */
    public boolean sendTestNudge(String toNumber) {
        InactiveReportConfig c = getConfig();
        List<Map<String, Object>> list = computeInactive(
                c.getRecentDays(), c.getLookbackDays(), c.getMinSessions(), c.getGameType(), c.getCooldownDays(), null);
        String msg = "[TEST] " + buildNudgeMessage(list.size(), c);
        List<String> failed = whatsAppService.sendToAll(List.of(toNumber), msg);
        return failed.isEmpty();
    }
}
